package com.jay.stagent.model;

import com.jay.stagent.model.enums.MarketRegime;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class MacroData {
    private LocalDate date;

    // Volatility
    private double indiaVix;

    // Nifty
    private double nifty50Price;
    private double nifty50Dma200;     // 200-day moving average of Nifty 50
    private double nifty50PctAboveDma200; // % above/below 200 DMA

    // FII/DII flows (net, in Crores)
    private double fiiNetFlowCr;
    private int consecutiveFiiSellingDays;

    // Macro indicators
    private double repoRatePct;       // RBI Repo rate
    private double cpiInflationPct;   // CPI inflation %
    private double usdInrRate;        // USD/INR exchange rate

    // Breadth
    private int advanceCount;
    private int declineCount;
    private double advanceDeclineRatio;

    // Derived
    private MarketRegime regime;
    private boolean newBuysSuppressed; // true when VIX > 25 or other hard suppression
}
