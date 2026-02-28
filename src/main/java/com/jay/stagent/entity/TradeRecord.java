package com.jay.stagent.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "trade_records")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeRecord {

    @Id
    @Column(name = "trade_id", length = 20)
    private String tradeId;

    private String symbol;
    private String exchange;
    private String sector;
    private String signalType;    // BUY/SELL/HOLD/REDUCE
    private String status;        // PENDING_APPROVAL/APPROVED/REJECTED/EXPIRED/EXECUTED/CANCELLED

    private double entryPrice;
    private double targetPrice;
    private double stopLossPrice;
    private double riskRewardRatio;
    private double capitalAllocationInr;
    private double confidenceScore;

    // Sub-scores
    private double fundamentalScore;
    private double technicalScore;
    private double macroScore;
    private double riskRewardScore;

    private LocalDateTime generatedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime approvedAt;
    private LocalDateTime executedAt;
    private LocalDateTime closedAt;

    // Outcome (filled when position is closed)
    private Double exitPrice;
    private Double realisedPnlInr;
    private Double realisedPnlPct;
    private String exitReason;
    private boolean targetHit;

    // Approval metadata
    private String rejectionReason;
    private String brokerOrderId;

    @Column(length = 500)
    private String fundamentalSummary;
    @Column(length = 500)
    private String technicalSummary;
    @Column(length = 300)
    private String macroContext;
}
