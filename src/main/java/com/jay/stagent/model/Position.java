package com.jay.stagent.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class Position {
    private String positionId;
    private String tradeId;
    private String symbol;
    private String exchange;
    private String sector;

    // Entry details
    private double entryPrice;
    private int quantity;
    private double investedAmountInr;
    private LocalDateTime entryTime;

    // Risk levels
    private double initialStopLoss;
    private double currentStopLoss;   // Can only move UP (trailing)
    private double targetPrice;

    // Current state
    private double currentPrice;
    private double unrealisedPnlInr;
    private double unrealisedPnlPct;

    // Exit details (filled when closed)
    private double exitPrice;
    private LocalDateTime exitTime;
    private double realisedPnlInr;
    private String exitReason;        // STOP_LOSS_HIT, TARGET_HIT, MANUAL, TIME_BASED

    private boolean active;
}
