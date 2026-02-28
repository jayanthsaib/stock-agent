package com.jay.stagent.layer3_signal;

import com.jay.stagent.config.AgentConfig;
import com.jay.stagent.layer1_data.DataIngestionEngine;
import com.jay.stagent.layer2_analysis.FundamentalAnalysisModule;
import com.jay.stagent.layer2_analysis.MacroContextModule;
import com.jay.stagent.layer2_analysis.TechnicalAnalysisModule;
import com.jay.stagent.model.FundamentalData;
import com.jay.stagent.model.MacroData;
import com.jay.stagent.model.StockAnalysisResult;
import com.jay.stagent.model.StockData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * On-demand single-stock analysis.
 * Runs the full pipeline (fundamental + technical + macro) for any symbol
 * the user provides — no confidence threshold filtering applied.
 *
 * Used by GET /api/analyse/{symbol} and GET /ui/analyse.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAnalysisService {

    private final DataIngestionEngine dataEngine;
    private final FundamentalAnalysisModule fundamentalModule;
    private final TechnicalAnalysisModule technicalModule;
    private final MacroContextModule macroModule;
    private final AgentConfig config;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public StockAnalysisResult analyse(String rawSymbol) {
        String symbol = rawSymbol.toUpperCase().trim();
        log.info("On-demand analysis requested for {}", symbol);

        try {
            // Always fetch fresh data for on-demand requests
            StockData stockData = dataEngine.getStockDataFresh(symbol);
            if (stockData == null || stockData.getLtp() <= 0) {
                return error(symbol, "Could not fetch market data for " + symbol
                    + ". Verify it is a valid NSE symbol (e.g. RELIANCE, TCS).");
            }

            MacroData macro = dataEngine.getMacroData();
            MacroContextModule.MacroResult macroResult = macroModule.analyse(macro);

            // Run fundamental + technical in parallel
            CompletableFuture<FundamentalAnalysisModule.FundamentalResult> fundamentalFuture =
                CompletableFuture.supplyAsync(() -> fundamentalModule.analyse(symbol), executor);
            CompletableFuture<TechnicalAnalysisModule.TechnicalResult> technicalFuture =
                CompletableFuture.supplyAsync(() -> technicalModule.analyse(stockData), executor);

            var fundamentalResult = fundamentalFuture.get(30, TimeUnit.SECONDS);
            var technicalResult   = technicalFuture.get(30, TimeUnit.SECONDS);

            // Trade level computation
            double entry  = stockData.getLtp();
            double sl     = computeStopLoss(entry, technicalResult.supportLevel());
            double target = computeTarget(entry, technicalResult.resistanceLevel());
            double rr     = computeRR(entry, sl, target);
            double rrScore = scoreRR(rr);

            // Composite score
            double macroScore = Math.max(0, macroResult.score() - macroResult.confidencePenalty());
            AgentConfig.ConfidenceWeights w = config.weights();
            double composite = fundamentalResult.score() * w.getFundamental()
                + technicalResult.score() * w.getTechnical()
                + macroScore * w.getMacro()
                + rrScore * w.getRiskReward();
            composite = Math.max(0, Math.min(100, composite));

            String verdict      = deriveVerdict(composite);
            String verdictColor = deriveVerdictColor(composite);

            FundamentalData fd = fundamentalResult.data();

            return StockAnalysisResult.builder()
                .symbol(symbol)
                .exchange("NSE")
                // Price
                .ltp(stockData.getLtp())
                .open(stockData.getOpen())
                .high(stockData.getHigh())
                .low(stockData.getLow())
                .close(stockData.getClose())
                .volume(stockData.getVolume())
                // Technical
                .sma200(technicalResult.sma200())
                .sma50(technicalResult.sma50())
                .sma20(technicalResult.sma20())
                .rsi(technicalResult.rsi())
                .supportLevel(technicalResult.supportLevel())
                .resistanceLevel(technicalResult.resistanceLevel())
                .goldenCross(technicalResult.goldenCross())
                .deathCross(technicalResult.deathCross())
                .volumeConfirmed(technicalResult.volumeConfirmed())
                .technicalScore(technicalResult.score())
                .technicalSummary(technicalResult.summary())
                // Fundamental
                .roe(fd != null ? fd.getRoe() : 0)
                .roce(fd != null ? fd.getRoce() : 0)
                .debtToEquity(fd != null ? fd.getDebtToEquity() : 0)
                .peRatio(fd != null ? fd.getPeRatio() : 0)
                .pegRatio(fd != null ? fd.getPegRatio() : 0)
                .promoterHolding(fd != null ? fd.getPromoterHoldingPct() : 0)
                .sector(fd != null && fd.getSector() != null ? fd.getSector() : "Unknown")
                .fundamentalScore(fundamentalResult.score())
                .fundamentalSummary(fundamentalResult.summary())
                // Macro
                .indiaVix(macro.getIndiaVix())
                .niftyPrice(macro.getNifty50Price())
                .nifty200dma(macro.getNifty50Dma200())
                .marketRegime(macro.getRegime() != null ? macro.getRegime().name() : "UNKNOWN")
                .macroScore(macroScore)
                .macroSummary(macroResult.summary())
                // Composite
                .compositeScore(composite)
                .fundamentalWeight(w.getFundamental() * 100)
                .technicalWeight(w.getTechnical() * 100)
                .macroWeight(w.getMacro() * 100)
                .rrWeight(w.getRiskReward() * 100)
                // Trade levels
                .suggestedEntry(entry)
                .suggestedTarget(target)
                .suggestedStopLoss(sl)
                .riskReward(rr)
                .rrScore(rrScore)
                // Verdict
                .verdict(verdict)
                .verdictColor(verdictColor)
                .analysedAt(LocalDateTime.now())
                .build();

        } catch (Exception e) {
            log.error("On-demand analysis failed for {}: {}", symbol, e.getMessage());
            return error(symbol, "Analysis failed: " + e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private double computeStopLoss(double entry, double support) {
        AgentConfig.Risk risk = config.risk();
        double slFromSupport = support > 0 ? support * 0.99
            : entry * (1 - risk.getMinStopLossPct() / 100);
        double minSl = entry * (1 - risk.getMaxStopLossPct() / 100);
        double maxSl = entry * (1 - risk.getMinStopLossPct() / 100);
        return Math.min(maxSl, Math.max(minSl, slFromSupport));
    }

    private double computeTarget(double entry, double resistance) {
        return resistance > entry * 1.03 ? resistance : entry * 1.10;
    }

    private double computeRR(double entry, double sl, double target) {
        double risk   = Math.abs(entry - sl);
        double reward = Math.abs(target - entry);
        return risk > 0 ? reward / risk : 0;
    }

    private double scoreRR(double rr) {
        if (rr >= 3.0) return 100;
        if (rr >= 2.5) return 85;
        if (rr >= 2.0) return 70;
        if (rr >= 1.5) return 40;
        return 0;
    }

    private String deriveVerdict(double score) {
        if (score >= 80) return "STRONG BUY";
        if (score >= 65) return "BUY";
        if (score >= 50) return "HOLD";
        return "AVOID";
    }

    private String deriveVerdictColor(double score) {
        if (score >= 80) return "#3fb950";
        if (score >= 65) return "#2ea043";
        if (score >= 50) return "#d29922";
        return "#f85149";
    }

    private StockAnalysisResult error(String symbol, String message) {
        return StockAnalysisResult.builder()
            .symbol(symbol)
            .errorMessage(message)
            .analysedAt(LocalDateTime.now())
            .build();
    }
}
