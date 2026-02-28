package com.jay.stagent.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class StockData {
    private String symbol;
    private String exchange;          // NSE or BSE
    private String isinCode;
    private double ltp;               // Last Traded Price
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
    private double avgVolume20d;      // 20-day average volume
    private double marketCapCr;       // Market cap in Crores
    private LocalDateTime fetchedAt;
    private List<OHLCVBar> historicalBars; // Daily OHLCV history (200+ days)
}
