package com.jay.stagent.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Full analysis result for a single stock.
 * Returned by StockAnalysisService and served by GET /api/analyse/{symbol}
 * and the /ui/analyse page.
 */
@Data
@Builder
public class StockAnalysisResult {

    private String symbol;
    private String exchange;

    // ── Price snapshot ────────────────────────────────────────────────────────
    private double ltp;
    private double open;
    private double high;
    private double low;
    private double close;
    private long   volume;

    // ── Technical ─────────────────────────────────────────────────────────────
    private double  sma200;
    private double  sma50;
    private double  sma20;
    private double  rsi;
    private double  supportLevel;
    private double  resistanceLevel;
    private boolean goldenCross;
    private boolean deathCross;
    private boolean volumeConfirmed;
    private double  technicalScore;
    private String  technicalSummary;

    // ── Fundamental ───────────────────────────────────────────────────────────
    private double roe;
    private double roce;
    private double debtToEquity;
    private double peRatio;
    private double pegRatio;
    private double promoterHolding;
    private String sector;
    private double fundamentalScore;
    private String fundamentalSummary;

    // ── Macro ─────────────────────────────────────────────────────────────────
    private double indiaVix;
    private double niftyPrice;
    private double nifty200dma;
    private String marketRegime;
    private double macroScore;
    private String macroSummary;

    // ── Composite score + weights ─────────────────────────────────────────────
    private double compositeScore;
    private double fundamentalWeight;   // e.g. 35 (%)
    private double technicalWeight;
    private double macroWeight;
    private double rrWeight;

    // ── Suggested trade levels ────────────────────────────────────────────────
    private double suggestedEntry;
    private double suggestedTarget;
    private double suggestedStopLoss;
    private double riskReward;
    private double rrScore;

    // ── Verdict & meta ────────────────────────────────────────────────────────
    private String        verdict;        // STRONG BUY / BUY / HOLD / AVOID
    private String        verdictColor;   // CSS colour token for the UI
    private LocalDateTime analysedAt;
    private String        errorMessage;   // non-null if analysis failed
}
