package com.jay.stagent.layer2_analysis;

import com.jay.stagent.config.AgentConfig;
import com.jay.stagent.model.MacroData;
import com.jay.stagent.model.enums.MarketRegime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Layer 2 — Macro Context Module.
 * Evaluates market-wide conditions to determine if the overall environment
 * supports new buy signals. Acts as a market-wide filter.
 * Can suppress all new buy signals or reduce confidence across the board.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MacroContextModule {

    private final AgentConfig config;

    public record MacroResult(
        double score,
        String summary,
        boolean newBuysSuppressed,
        int confidencePenalty,
        MarketRegime regime
    ) {}

    /**
     * Evaluates macro conditions and returns a score 0-100.
     * If new buys are suppressed (VIX > 25, bear market), returns (0, suppressed=true).
     */
    public MacroResult analyse(MacroData macro) {
        AgentConfig.Macro cfg = config.macro();
        StringBuilder summary = new StringBuilder();

        // ── Hard Suppression Checks ────────────────────────────────────────────
        if (macro.isNewBuysSuppressed()) {
            String reason = macro.getIndiaVix() > cfg.getVixNoBuysThreshold()
                ? String.format("India VIX=%.1f > %.0f — all new buys SUPPRESSED",
                    macro.getIndiaVix(), cfg.getVixNoBuysThreshold())
                : "Nifty significantly below 200 DMA — bear market mode";
            return new MacroResult(0, reason, true, 0, macro.getRegime());
        }

        double score = 50.0; // Neutral baseline
        int confidencePenalty = 0;

        // ── India VIX ──────────────────────────────────────────────────────────
        double vix = macro.getIndiaVix();
        if (vix < cfg.getVixFavorableThreshold()) {
            score += 20;
            summary.append(String.format("VIX=%.1f favorable ✓. ", vix));
        } else if (vix < cfg.getVixCautionThreshold()) {
            score += 8;
            summary.append(String.format("VIX=%.1f neutral. ", vix));
        } else {
            score -= 15;
            confidencePenalty += 10;
            summary.append(String.format("VIX=%.1f elevated — caution. ", vix));
        }

        // ── Nifty vs 200 DMA ──────────────────────────────────────────────────
        double pctAbove = macro.getNifty50PctAboveDma200();
        if (pctAbove > 0 && pctAbove <= 10) {
            score += 15;
            summary.append(String.format("Nifty %.1f%% above 200 DMA ✓. ", pctAbove));
        } else if (pctAbove > 10 && pctAbove <= 20) {
            score += 8; // Bull market — require more conviction
            confidencePenalty += 5;
            summary.append(String.format("Nifty %.1f%% above 200 DMA — avoid FOMO tops. ", pctAbove));
        } else if (pctAbove <= 0 && pctAbove > -5) {
            score -= 8;
            summary.append("Nifty near/below 200 DMA — defensive mode. ");
        } else if (pctAbove <= -5) {
            score -= 20;
            summary.append("Nifty well below 200 DMA — bear market warning. ");
        } else {
            // pctAbove > 20 — significantly extended
            score -= 5;
            confidencePenalty += 8;
            summary.append(String.format("Nifty %.1f%% above 200 DMA — extended. ", pctAbove));
        }

        // ── FII Flow ──────────────────────────────────────────────────────────
        int fiiSellingDays = macro.getConsecutiveFiiSellingDays();
        if (fiiSellingDays >= cfg.getFiiSellingDaysThreshold()) {
            score -= 15;
            confidencePenalty += 15;
            summary.append(String.format("FII selling %d consecutive days ✗. ", fiiSellingDays));
        } else if (macro.getFiiNetFlowCr() > 0) {
            score += 10;
            summary.append("FII net buying ✓. ");
        } else if (macro.getFiiNetFlowCr() < -1000) {
            score -= 5;
            summary.append("FII net selling — caution. ");
        }

        // ── Market Regime Adjustments ─────────────────────────────────────────
        switch (macro.getRegime()) {
            case BULL -> {
                score += 10;
                summary.append("Bull market regime ✓. ");
            }
            case BEAR -> {
                score -= 20;
                summary.append("Bear market regime ✗. ");
            }
            case HIGH_VOLATILITY -> {
                score -= 10;
                summary.append("High volatility regime — reduce sizes. ");
            }
            case SIDEWAYS -> {
                summary.append("Sideways market — selective entries only. ");
            }
        }

        // ── RBI Rate Cycle ────────────────────────────────────────────────────
        // Rate hike cycles are hawkish — reduce score for rate-sensitive sectors
        // This is a simplified check; in production, track rate direction
        if (macro.getRepoRatePct() > 6.5) {
            score -= 5;
            summary.append("Elevated repo rate — NBFC/real estate caution. ");
        }

        score = Math.max(0, Math.min(100, score));
        log.debug("Macro score: {:.1f} | VIX={} Nifty/200dma={:.1f}% Regime={}",
            score, vix, pctAbove, macro.getRegime());

        return new MacroResult(score, summary.toString().trim(), false, confidencePenalty, macro.getRegime());
    }
}
