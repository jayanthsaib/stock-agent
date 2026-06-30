package com.jay.stagent.layer3_signal;

import com.jay.stagent.config.AgentConfig;
import com.jay.stagent.layer1_data.DataIngestionEngine;
import com.jay.stagent.layer1_data.MFDataIngestionEngine;
import com.jay.stagent.layer1_data.PortfolioValueService;
import com.jay.stagent.layer2_analysis.MacroContextModule;
import com.jay.stagent.layer2_analysis.MutualFundAnalysisModule;
import com.jay.stagent.model.ConfidenceScore;
import com.jay.stagent.model.MFSchemeData;
import com.jay.stagent.model.MacroData;
import com.jay.stagent.model.TradeSignal;
import com.jay.stagent.model.enums.RiskLevel;
import com.jay.stagent.model.enums.SignalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer 3 — Mutual Fund Signal Generator.
 * Generates SIP and lump-sum recommendations for all cached AMFI equity schemes.
 * Mirrors the stock SignalGenerator pattern — same TradeSignal → approval pipeline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MFSignalGenerator {

    private final MFDataIngestionEngine mfDataEngine;
    private final MutualFundAnalysisModule mfAnalysis;
    private final MacroContextModule macroModule;
    private final DataIngestionEngine dataEngine;
    private final PortfolioValueService portfolioValueService;
    private final AgentConfig config;

    // Weekly dedup — prevents re-signaling the same scheme in the same calendar week
    private final Set<String> weeklySignaledSchemes = ConcurrentHashMap.newKeySet();
    private volatile LocalDate weeklySignaledWeek;

    public void resetWeeklySignals() {
        weeklySignaledSchemes.clear();
        weeklySignaledWeek = null;
    }

    /**
     * Generates MF signals for all cached schemes above the confidence threshold.
     */
    public List<TradeSignal> generateSignals() {
        AgentConfig.MutualFunds cfg = config.mutualFunds();
        if (!cfg.isEnabled()) {
            log.info("MF signal generation skipped — mutual_funds.enabled: false");
            return List.of();
        }

        Map<String, MFSchemeData> schemes = mfDataEngine.getCachedSchemes();
        if (schemes.isEmpty()) {
            log.warn("MF signal generation skipped — scheme cache is empty, run refresh first");
            return List.of();
        }

        // Reset dedup if we're in a new week
        LocalDate today = LocalDate.now();
        if (!today.equals(weeklySignaledWeek) &&
            (weeklySignaledWeek == null || today.getDayOfWeek().getValue() <
             weeklySignaledWeek.getDayOfWeek().getValue())) {
            weeklySignaledSchemes.clear();
            weeklySignaledWeek = today;
        }

        MacroData macro = dataEngine.getMacroData();
        MacroContextModule.MacroResult macroResult = macroModule.analyse(macro);
        double benchmarkCagr3y = cfg.getBenchmarkCagr3yPct();
        double portfolioValue = portfolioValueService.getPortfolioValue();

        log.info("MFSignalGenerator: analysing {} schemes (macro suppressed={})",
            schemes.size(), macroResult.newBuysSuppressed());

        List<TradeSignal> signals = new ArrayList<>();
        for (MFSchemeData scheme : schemes.values()) {
            try {
                TradeSignal signal = analyseScheme(scheme, macroResult, benchmarkCagr3y,
                    portfolioValue, cfg);
                if (signal != null) {
                    signals.add(signal);
                    weeklySignaledSchemes.add(scheme.getSchemeCode());
                }
            } catch (Exception e) {
                log.debug("MF analysis failed for {}: {}", scheme.getSchemeCode(), e.getMessage());
            }
        }

        log.info("MFSignalGenerator: {} signals generated from {} schemes",
            signals.size(), schemes.size());
        return signals;
    }

    private TradeSignal analyseScheme(MFSchemeData scheme,
                                       MacroContextModule.MacroResult macroResult,
                                       double benchmarkCagr3y,
                                       double portfolioValue,
                                       AgentConfig.MutualFunds cfg) {
        // Skip already signaled this week
        if (weeklySignaledSchemes.contains(scheme.getSchemeCode())) return null;

        MutualFundAnalysisModule.MutualFundResult mfResult =
            mfAnalysis.analyse(scheme, benchmarkCagr3y);

        double score = mfResult.score();

        // Determine signal type and mode
        String mfSignalMode;
        SignalType signalType;
        double capitalAllocation;
        int holdingDays;

        boolean lumpsumOpportunity = score >= 65
            && scheme.getCagr3y() >= benchmarkCagr3y
            && scheme.getNav52wHigh() > 0
            && (scheme.getNav52wHigh() - scheme.getCurrentNav()) / scheme.getNav52wHigh() * 100 >= 15
            && !macroResult.newBuysSuppressed();

        if (lumpsumOpportunity) {
            mfSignalMode = "LUMP_SUM";
            signalType = SignalType.BUY;
            capitalAllocation = cfg.getDefaultLumpsumAmountInr();
            holdingDays = 1095;
        } else if (score >= cfg.getMinScoreToNotify() && scheme.isDirect()
                && scheme.getConsistencyCount() >= 6) {
            mfSignalMode = "SIP";
            signalType = SignalType.BUY;
            capitalAllocation = cfg.getDefaultSipAmountInr();
            holdingDays = 365;
        } else if (score >= 50 && score < cfg.getMinScoreToNotify()) {
            mfSignalMode = "SIP_CONTINUE";
            signalType = SignalType.HOLD;
            capitalAllocation = cfg.getDefaultSipAmountInr();
            holdingDays = 365;
        } else if (score < 40 && scheme.getCagr3y() < 8) {
            mfSignalMode = "SIP_STOP";
            signalType = SignalType.SELL;
            capitalAllocation = 0;
            holdingDays = 0;
        } else {
            return null; // Below threshold, not actionable
        }

        // Drop HOLD and SELL unless they meet a minimum usefulness bar
        if (signalType == SignalType.HOLD || signalType == SignalType.SELL) {
            if (score < 40 && signalType == SignalType.HOLD) return null;
        }

        // Confidence score — map quality to the 4-dimension structure
        double macroScore = macroResult.newBuysSuppressed() ? 30.0
            : Math.max(0, macroResult.score() - macroResult.confidencePenalty());
        double rrScore = 70.0; // long-term MF investing has inherent positive expectancy

        ConfidenceScore cs = ConfidenceScore.builder()
            .fundamentalScore(score)
            .technicalScore(score)
            .macroScore(macroScore)
            .riskRewardScore(rrScore)
            .build();
        cs.calculate(config.weights());

        if (cs.getComposite() < cfg.getMinScoreToNotify()) return null;

        // Pricing
        double currentNav = scheme.getCurrentNav();
        double targetNav = currentNav * (1 + scheme.getCagr3y() / 100);

        // Capital allocation % of portfolio
        double capitalPct = portfolioValue > 0 ? capitalAllocation / portfolioValue * 100 : 0;
        double availableCash = portfolioValue * (1 - config.portfolio().getEmergencyCashBufferPct() / 100);
        double postTradeCash = availableCash - capitalAllocation;
        boolean cashSafe = postTradeCash >= portfolioValue * config.portfolio().getEmergencyCashBufferPct() / 100;

        // Risk level
        RiskLevel riskLevel;
        String cat = scheme.getCategory() != null ? scheme.getCategory().toLowerCase() : "";
        if (cat.contains("small cap") || cat.contains("thematic") || cat.contains("sector")) {
            riskLevel = RiskLevel.HIGH;
        } else if (cat.contains("mid cap")) {
            riskLevel = RiskLevel.MODERATE;
        } else {
            riskLevel = RiskLevel.LOW;
        }

        // Scenario narratives
        double lumpsumGain = capitalAllocation * scheme.getCagr3y() / 100;
        String worstCase = String.format("If market corrects 20%%, NAV may drop to ₹%.2f. " +
            "Continue SIP to average cost. No stop-loss set.", currentNav * 0.8);
        String bullCase = String.format("If %.1f%% CAGR continues, 1Y target NAV ₹%.2f. " +
            "₹%.0f investment → ₹%.0f gain estimate.",
            scheme.getCagr3y(), targetNav, capitalAllocation, lumpsumGain);
        String invalidation = String.format(
            "No stop-loss. Review if 3Y CAGR drops below %.0f%% at next quarterly review.",
            benchmarkCagr3y - 2);

        String technicalSummary = String.format("NAV ₹%.2f | 1Y: %.1f%% | 6M: %.1f%% | 3M: %.1f%% | " +
            "52W High: ₹%.2f | 52W Low: ₹%.2f",
            currentNav, mfResult.return1y(), mfResult.return6m(), mfResult.return3m(),
            scheme.getNav52wHigh(), scheme.getNav52wLow());

        LocalDateTime now = LocalDateTime.now();
        return TradeSignal.builder()
            .symbol(scheme.getSchemeCode())
            .exchange("AMFI")
            .assetType("Mutual Fund")
            .sector(scheme.getCategory())
            .signalType(signalType)
            .mfSignalMode(mfSignalMode)
            .intraday(false)
            .entryPrice(currentNav)
            .targetPrice(targetNav)
            .stopLossPrice(0.0)
            .riskRewardRatio(0.0)
            .expectedHoldingDays(holdingDays)
            .riskLevel(riskLevel)
            .confidenceScore(cs)
            .capitalAllocationInr(capitalAllocation)
            .capitalAllocationPct(capitalPct)
            .availableCashInr(availableCash)
            .postTradeCashInr(postTradeCash)
            .cashBufferSafe(cashSafe)
            .fundamentalSummary(mfResult.summary())
            .technicalSummary(technicalSummary)
            .macroContext(macroResult.summary())
            .worstCaseScenario(worstCase)
            .bullCaseScenario(bullCase)
            .invalidationLevel(invalidation)
            .generatedAt(now)
            .expiresAt(now.plusDays(7))
            .build();
    }
}
