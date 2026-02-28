package com.jay.stagent.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FundamentalData {
    private String symbol;

    // Revenue & Profit
    private double revenueCagr3y;     // 3-year revenue CAGR (%)
    private double revenueCagr5y;     // 5-year revenue CAGR (%)
    private double netProfitCagr3y;   // Net profit CAGR (%)

    // Efficiency
    private double roe;               // Return on Equity (%)
    private double roce;              // Return on Capital Employed (%)

    // Leverage
    private double debtToEquity;      // D/E ratio

    // Cash Flow
    private double operatingCashFlow; // Latest year OCF (Cr)
    private double freeCashFlow;      // Latest year FCF (Cr)
    private int positiveCfYears;      // Years with positive OCF in last 5

    // Shareholding
    private double promoterHoldingPct;    // Promoter holding (%)
    private double promoterPledgedPct;    // Pledged % of promoter holding

    // Valuation
    private double peRatio;
    private double pbRatio;
    private double pegRatio;
    private double sectorMedianPe;

    // Sector
    private String sector;
    private int sectorOutlookScore;  // 1-10 scored manually/from API
}
