package com.jay.stagent.layer3_signal;

import com.jay.stagent.config.AgentConfig;
import com.jay.stagent.layer1_data.DataIngestionEngine;
import com.jay.stagent.layer1_data.PortfolioValueService;
import com.jay.stagent.layer2_analysis.FundamentalAnalysisModule;
import com.jay.stagent.layer2_analysis.MacroContextModule;
import com.jay.stagent.layer2_analysis.TechnicalAnalysisModule;
import com.jay.stagent.model.*;
import com.jay.stagent.model.enums.RiskLevel;
import com.jay.stagent.model.enums.SignalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Layer 3 — Signal Generator.
 * Runs all analysis modules in parallel, aggregates their scores into a
 * weighted ConfidenceScore, and constructs a complete TradeSignal.
 *
 * Only signals with composite score >= min_confidence_to_notify are returned.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalGenerator {

    private final DataIngestionEngine dataEngine;
    private final FundamentalAnalysisModule fundamentalModule;
    private final TechnicalAnalysisModule technicalModule;
    private final MacroContextModule macroModule;
    private final AgentConfig config;
    private final PortfolioValueService portfolioValueService;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Runs the full analysis pipeline on all watchlist stocks and returns
     * trade signals that pass the minimum confidence threshold.
     */
    public List<TradeSignal> generateSignals() {
        log.info("SignalGenerator: starting analysis across full equity universe");

        MacroData macro = dataEngine.getMacroData();

        // Check macro suppression first — if active, skip stock analysis
        MacroContextModule.MacroResult macroResult = macroModule.analyse(macro);
        if (macroResult.newBuysSuppressed()) {
            log.warn("New buys suppressed by macro conditions: {}", macroResult.summary());
            return List.of();
        }

        List<TradeSignal> signals = new ArrayList<>();
        List<StockData> allStockData = dataEngine.getAllEquityData();

        // Run analysis for each stock in parallel using virtual threads
        List<CompletableFuture<TradeSignal>> futures = allStockData.stream()
            .map(stockData -> CompletableFuture.supplyAsync(
                () -> analyseStock(stockData, macro, macroResult), executor))
            .toList();

        for (CompletableFuture<TradeSignal> future : futures) {
            try {
                TradeSignal signal = future.get();
                if (signal != null) {
                    signals.add(signal);
                }
            } catch (Exception e) {
                log.error("Signal generation future failed: {}", e.getMessage());
            }
        }

        log.info("SignalGenerator: generated {} signals above threshold {:.0f}%",
            signals.size(), config.signal().getMinConfidenceToNotify());
        return signals;
    }

    // ── Per-Stock Analysis ────────────────────────────────────────────────────

    private TradeSignal analyseStock(StockData stock, MacroData macro,
                                     MacroContextModule.MacroResult macroResult) {
        String symbol = stock.getSymbol();
        try {
            // Run fundamental + technical analysis in parallel
            CompletableFuture<FundamentalAnalysisModule.FundamentalResult> fundamentalFuture =
                CompletableFuture.supplyAsync(() -> fundamentalModule.analyse(symbol), executor);
            CompletableFuture<TechnicalAnalysisModule.TechnicalResult> technicalFuture =
                CompletableFuture.supplyAsync(() -> technicalModule.analyse(stock), executor);

            var fundamentalResult = fundamentalFuture.get();
            var technicalResult   = technicalFuture.get();

            // Build confidence score
            ConfidenceScore score = ConfidenceScore.builder()
                .fundamentalScore(fundamentalResult.score())
                .technicalScore(technicalResult.score())
                .macroScore(Math.max(0, macroResult.score() - macroResult.confidencePenalty()))
                .riskRewardScore(0) // Will be set after computing SL/target
                .fundamentalReason(fundamentalResult.summary())
                .technicalReason(technicalResult.summary())
                .macroReason(macroResult.summary())
                .build();

            // Hard fundamental disqualifier — score of 0 means disqualified
            if (fundamentalResult.score() == 0) {
                log.debug("{} disqualified by fundamental analysis", symbol);
                return null;
            }

            // Compute entry, SL, target from technical levels
            double entryPrice  = stock.getLtp();
            double stopLoss    = computeStopLoss(entryPrice, technicalResult.supportLevel());
            double target      = computeTarget(entryPrice, technicalResult.resistanceLevel());
            double rrRatio     = computeRR(entryPrice, stopLoss, target);

            // Score R:R
            double rrScore = scoreRiskReward(rrRatio);
            score.setRiskRewardScore(rrScore);
            score.setRiskRewardReason(String.format("R:R = 1:%.1f", rrRatio));
            score.calculate(config.weights());

            // Filter by minimum confidence
            if (score.getComposite() < config.signal().getMinConfidenceToNotify()) {
                log.debug("{} below threshold: score={:.1f}", symbol, score.getComposite());
                return null;
            }

            // Capital allocation — use live wallet value, not hardcoded config
            double totalPortfolio = portfolioValueService.getPortfolioValue();
            double allocationPct  = config.positionSizing().getMaxSingleStockPct() / 100.0;
            double allocationInr  = totalPortfolio * allocationPct;

            double postTradeCash = totalPortfolio
                - (totalPortfolio * config.portfolio().getEmergencyCashBufferPct() / 100)
                - allocationInr;
            boolean cashBufferSafe = postTradeCash >= 0;

            // Build signal
            return TradeSignal.builder()
                .symbol(symbol)
                .exchange(stock.getExchange())
                .assetType("Stock")
                .signalType(SignalType.BUY)
                .entryPrice(entryPrice)
                .targetPrice(target)
                .stopLossPrice(stopLoss)
                .riskRewardRatio(rrRatio)
                .expectedHoldingDays(estimateHoldingDays(rrRatio, technicalResult))
                .riskLevel(classifyRisk(score.getComposite(), rrRatio))
                .confidenceScore(score)
                .capitalAllocationInr(allocationInr)
                .capitalAllocationPct(config.positionSizing().getMaxSingleStockPct())
                .postTradeCashInr(postTradeCash)
                .cashBufferSafe(cashBufferSafe)
                .sector(fundamentalResult.data() != null ? fundamentalResult.data().getSector() : "Unknown")
                .fundamentalSummary(fundamentalResult.summary())
                .technicalSummary(technicalResult.summary())
                .macroContext(macroResult.summary())
                .worstCaseScenario(buildWorstCase(allocationInr, entryPrice, stopLoss))
                .bullCaseScenario(buildBullCase(allocationInr, entryPrice, target))
                .invalidationLevel(String.format("Price closes below ₹%.2f", stopLoss))
                .generatedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMinutes(config.signal().getApprovalWindowMinutes()))
                .build();

        } catch (Exception e) {
            log.error("Analysis failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    // ── Price Level Computation ────────────────────────────────────────────────

    private double computeStopLoss(double entry, double support) {
        AgentConfig.Risk risk = config.risk();
        // Stop-loss: 1-2% below nearest strong support, but within configured bounds
        double slFromSupport = support > 0 ? support * 0.99 : entry * (1 - risk.getMinStopLossPct() / 100);
        double minSl = entry * (1 - risk.getMaxStopLossPct() / 100);
        double maxSl = entry * (1 - risk.getMinStopLossPct() / 100);
        return Math.min(maxSl, Math.max(minSl, slFromSupport));
    }

    private double computeTarget(double entry, double resistance) {
        // Target: next meaningful resistance, minimum 2x the stop-loss distance
        if (resistance > entry * 1.03) return resistance;
        return entry * 1.10; // Default: 10% target if no clear resistance
    }

    private double computeRR(double entry, double stopLoss, double target) {
        double risk   = Math.abs(entry - stopLoss);
        double reward = Math.abs(target - entry);
        return risk > 0 ? reward / risk : 0;
    }

    private double scoreRiskReward(double rrRatio) {
        if (rrRatio >= 3.0) return 100;
        if (rrRatio >= 2.5) return 85;
        if (rrRatio >= 2.0) return 70;
        if (rrRatio >= 1.5) return 40;
        return 0; // Below minimum R:R
    }

    private int estimateHoldingDays(double rrRatio, TechnicalAnalysisModule.TechnicalResult tech) {
        // Higher R:R and weaker momentum = longer holding expected
        if (rrRatio >= 2.5) return 30;
        if (rrRatio >= 2.0) return 20;
        return 15;
    }

    private RiskLevel classifyRisk(double score, double rr) {
        if (score >= 75 && rr >= 2.5) return RiskLevel.LOW;
        if (score >= 60 && rr >= 2.0) return RiskLevel.MODERATE;
        return RiskLevel.HIGH;
    }

    private String buildWorstCase(double capital, double entry, double sl) {
        double lossInr = capital * Math.abs(entry - sl) / entry;
        double lossPct = (lossInr / capital) * 100;
        return String.format("If stop-loss hit → Loss of ₹%.0f (%.1f%% of allocated capital)", lossInr, lossPct);
    }

    private String buildBullCase(double capital, double entry, double target) {
        double gainInr = capital * Math.abs(target - entry) / entry;
        double gainPct = (gainInr / capital) * 100;
        return String.format("If target hit → Gain of ₹%.0f (%.1f%% return on trade)", gainInr, gainPct);
    }
}
