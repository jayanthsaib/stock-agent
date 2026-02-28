package com.jay.stagent.layer5_report;

import com.jay.stagent.layer4_risk.RiskValidator;
import com.jay.stagent.model.TradeSignal;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * Layer 5 — Pre-Trade Report Generator.
 * Produces the mandatory human-readable report for every trade signal,
 * formatted exactly as defined in the blueprint.
 * No trade is executed without this report being sent and approved.
 */
@Component
public class PreTradeReportGenerator {

    private static final String DIVIDER =
        "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm");

    /**
     * Generates the full pre-trade report text for a trade signal.
     * Includes all fields from the blueprint template.
     */
    public String generate(TradeSignal signal, RiskValidator.ValidationResult validation) {
        var cs = signal.getConfidenceScore();
        String timestamp = signal.getGeneratedAt() != null
            ? signal.getGeneratedAt().format(FMT) : "NOW";
        String expiresAt = signal.getExpiresAt() != null
            ? signal.getExpiresAt().format(FMT) : "30 min";

        StringBuilder sb = new StringBuilder();
        sb.append("📊 PRE-TRADE ANALYSIS REPORT  —  ").append(timestamp).append("\n");
        sb.append(DIVIDER).append("\n");
        sb.append(String.format("TRADE ID          :  %s%n", signal.getTradeId()));
        sb.append(String.format("ASSET NAME        :  %s (%s: %s)%n",
            signal.getSymbol(), signal.getExchange(), signal.getSymbol()));
        sb.append(String.format("ASSET TYPE        :  %s%n", signal.getAssetType()));
        sb.append(String.format("SIGNAL TYPE       :  %s%n", signal.getSignalType()));
        sb.append(DIVIDER).append("\n");
        sb.append(String.format("BUY PRICE         :  ₹%.2f  (Limit order)%n", signal.getEntryPrice()));
        sb.append(String.format("TARGET PRICE      :  ₹%.2f%n", signal.getTargetPrice()));
        sb.append(String.format("STOP-LOSS PRICE   :  ₹%.2f  (NEVER moved down)%n", signal.getStopLossPrice()));
        sb.append(String.format("RISK-REWARD RATIO :  1 : %.1f%n", signal.getRiskRewardRatio()));
        sb.append(String.format("EXPECTED HOLD     :  %d days%n", signal.getExpectedHoldingDays()));
        sb.append(String.format("RISK LEVEL        :  %s%n", signal.getRiskLevel()));
        sb.append(String.format("CONFIDENCE SCORE  :  %.0f%%  [%s]%n",
            cs.getComposite(), cs.breakdownString()));
        sb.append(String.format("CLASSIFICATION    :  %s%n", cs.classification()));
        sb.append(DIVIDER).append("\n");
        sb.append(String.format("CAPITAL ALLOC     :  ₹%.0f  (%.1f%% of portfolio)%n",
            signal.getCapitalAllocationInr(), signal.getCapitalAllocationPct()));
        sb.append(String.format("POST-TRADE CASH   :  ₹%.0f  (Buffer: %s)%n",
            signal.getPostTradeCashInr(),
            signal.isCashBufferSafe() ? "✅ SAFE" : "⚠️ CHECK"));
        sb.append(DIVIDER).append("\n");
        sb.append(String.format("📈 FUNDAMENTAL    :  %s%n", signal.getFundamentalSummary()));
        sb.append(String.format("📉 TECHNICAL      :  %s%n", signal.getTechnicalSummary()));
        sb.append(String.format("🌐 MACRO CONTEXT  :  %s%n", signal.getMacroContext()));
        sb.append(DIVIDER).append("\n");
        sb.append(String.format("❌ WORST CASE     :  %s%n", signal.getWorstCaseScenario()));
        sb.append(String.format("✅ BULL CASE      :  %s%n", signal.getBullCaseScenario()));
        sb.append(String.format("⛔ INVALIDATION   :  %s%n", signal.getInvalidationLevel()));
        sb.append(DIVIDER).append("\n");

        // Warnings from risk validator
        if (!validation.warnings().isEmpty()) {
            sb.append("⚠️  RISK WARNINGS:\n");
            validation.warnings().forEach(w -> sb.append("   • ").append(w).append("\n"));
            sb.append(DIVIDER).append("\n");
        }

        sb.append(String.format("📲 Reply:  APPROVE %s  or  REJECT %s [reason]%n",
            signal.getTradeId(), signal.getTradeId()));
        sb.append(String.format("⏰ Signal expires at:  %s%n", expiresAt));
        sb.append("─────────────────────────────────────────────────────────\n");

        return sb.toString();
    }

    /**
     * Generates a short summary notification (used for already-approved/executed signals).
     */
    public String generateExecutionConfirmation(TradeSignal signal, String brokerOrderId) {
        return String.format(
            "✅ ORDER PLACED%n" +
            "Trade ID  : %s%n" +
            "Symbol    : %s @ ₹%.2f%n" +
            "Qty       : %d shares%n" +
            "Stop-loss : ₹%.2f%n" +
            "Target    : ₹%.2f%n" +
            "Order ID  : %s%n",
            signal.getTradeId(), signal.getSymbol(), signal.getEntryPrice(),
            (int)(signal.getCapitalAllocationInr() / signal.getEntryPrice()),
            signal.getStopLossPrice(), signal.getTargetPrice(), brokerOrderId
        );
    }

    /** Generates a rejection notification */
    public String generateRejectionAck(String tradeId, String reason) {
        return String.format("❌ SIGNAL REJECTED%nTrade ID: %s%nReason: %s%n" +
            "Signal archived for learning module.", tradeId, reason);
    }

    /** Generates an expiry notification */
    public String generateExpiryNotification(String tradeId) {
        return String.format("⏰ SIGNAL EXPIRED%nTrade ID: %s%n" +
            "No response received — signal auto-expired. No trade placed.", tradeId);
    }
}
