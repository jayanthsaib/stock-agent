package com.jay.stagent.model;

import com.jay.stagent.model.enums.RiskLevel;
import com.jay.stagent.model.enums.SignalStatus;
import com.jay.stagent.model.enums.SignalType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class TradeSignal {

    @Builder.Default
    private String tradeId = "TRD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    private String symbol;
    private String exchange;
    private String assetType;            // Stock, Mutual Fund, ETF

    private SignalType signalType;

    // Pricing
    private double entryPrice;
    private double targetPrice;
    private double stopLossPrice;
    private double riskRewardRatio;

    // Risk
    private int expectedHoldingDays;
    private RiskLevel riskLevel;
    private ConfidenceScore confidenceScore;

    // Capital
    private double capitalAllocationInr;
    private double capitalAllocationPct;

    // Portfolio context at time of signal
    private int openPositionsCount;
    private double deployedPct;
    private double availableCashInr;
    private double postTradeCashInr;
    private boolean cashBufferSafe;

    // Analysis summaries (for report)
    private String fundamentalSummary;
    private String technicalSummary;
    private String macroContext;
    private String worstCaseScenario;
    private String bullCaseScenario;
    private String invalidationLevel;

    // Sector
    private String sector;

    // Lifecycle
    @Builder.Default
    private SignalStatus status = SignalStatus.PENDING_APPROVAL;
    private LocalDateTime generatedAt;
    private LocalDateTime expiresAt;
    private String rejectionReason;

    // Execution result
    private String brokerOrderId;
    private double executedPrice;
    private LocalDateTime executedAt;

    /** Loss amount if stop-loss is hit */
    public double maxLossInr() {
        return capitalAllocationInr * (Math.abs(entryPrice - stopLossPrice) / entryPrice);
    }

    /** Gain amount if target is hit */
    public double maxGainInr() {
        return capitalAllocationInr * (Math.abs(targetPrice - entryPrice) / entryPrice);
    }

    public double maxLossPct() {
        return capitalAllocationPct * (Math.abs(entryPrice - stopLossPrice) / entryPrice);
    }
}
