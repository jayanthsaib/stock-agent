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

    private static final String LINE = "─────────────────────────────";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd-MMM HH:mm");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    public String generate(TradeSignal signal, RiskValidator.ValidationResult validation) {
        if ("Mutual Fund".equals(signal.getAssetType())) {
            return generateMF(signal, validation);
        }

        var cs = signal.getConfidenceScore();
        String emoji = signal.getSignalType().name().equals("BUY") ? "📈" : "📉";
        String expires = signal.getExpiresAt() != null
            ? signal.getExpiresAt().format(FMT) : "—";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s <b>%s  %s</b>  •  %s  •  <b>%.0f%%</b>%n",
            emoji, signal.getSignalType(), signal.getSymbol(),
            signal.getExchange(), cs.getComposite()));
        sb.append(LINE).append("\n");
        sb.append(String.format("Entry <b>₹%.2f</b>  |  Target <b>₹%.2f</b>  |  SL <b>₹%.2f</b>%n",
            signal.getEntryPrice(), signal.getTargetPrice(), signal.getStopLossPrice()));
        sb.append(String.format("R:R 1:%.1f  •  Hold ~%dd  •  ₹%.0f alloc%n",
            signal.getRiskRewardRatio(), signal.getExpectedHoldingDays(),
            signal.getCapitalAllocationInr()));
        sb.append(LINE).append("\n");
        sb.append(String.format("F:%.0f  T:%.0f  M:%.0f  RR:%.0f%n",
            cs.getFundamentalScore(), cs.getTechnicalScore(),
            cs.getMacroScore(), cs.getRiskRewardScore()));
        if (signal.getFundamentalSummary() != null && !signal.getFundamentalSummary().isBlank())
            sb.append("📊 ").append(truncate(signal.getFundamentalSummary(), 120)).append("\n");
        if (signal.getTechnicalSummary() != null && !signal.getTechnicalSummary().isBlank())
            sb.append("📉 ").append(truncate(signal.getTechnicalSummary(), 120)).append("\n");
        if (!validation.warnings().isEmpty()) {
            sb.append("⚠️ ");
            sb.append(String.join(" | ", validation.warnings()));
            sb.append("\n");
        }
        sb.append(LINE).append("\n");
        sb.append(String.format("✅ <code>APPROVE %s</code>  |  ❌ <code>REJECT %s</code>%n",
            signal.getTradeId(), signal.getTradeId()));
        sb.append(String.format("⏰ %s  •  %s%n", expires, signal.getTradeId()));

        return sb.toString();
    }

    private String generateMF(TradeSignal signal, RiskValidator.ValidationResult validation) {
        var cs = signal.getConfidenceScore();
        String mode = signal.getMfSignalMode() != null ? signal.getMfSignalMode() : "BUY";
        String modeLabel = switch (mode) {
            case "SIP"          -> "SIP Start";
            case "SIP_CONTINUE" -> "SIP Continue";
            case "SIP_STOP"     -> "SIP Stop/Redeem";
            case "LUMP_SUM"     -> "Lump Sum";
            default             -> mode;
        };
        String modeEmoji = switch (mode) {
            case "SIP_STOP" -> "🔴";
            case "SIP_CONTINUE" -> "🟡";
            default -> "💰";
        };
        String expires = signal.getExpiresAt() != null
            ? signal.getExpiresAt().format(DATE_FMT) : "7 days";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s <b>%s  %s</b>  •  <b>%.0f%%</b>%n",
            modeEmoji, modeLabel, signal.getSymbol(), cs.getComposite()));
        sb.append(String.format("%s  •  %s%n", signal.getSector(),
            signal.getFundamentalSummary() != null
                ? truncate(signal.getFundamentalSummary().split("\\.")[0], 40) : ""));
        sb.append(LINE).append("\n");
        sb.append(String.format("NAV <b>₹%.4f</b>  |  Target <b>₹%.4f</b>  |  ₹%.0f%n",
            signal.getEntryPrice(), signal.getTargetPrice(),
            signal.getCapitalAllocationInr()));
        sb.append(String.format("Quality %.0f%%  •  Macro %.0f%%  •  %d-day horizon%n",
            cs.getFundamentalScore(), cs.getMacroScore(), signal.getExpectedHoldingDays()));
        if (signal.getTechnicalSummary() != null && !signal.getTechnicalSummary().isBlank())
            sb.append("📉 ").append(truncate(signal.getTechnicalSummary(), 120)).append("\n");
        if (!validation.warnings().isEmpty()) {
            sb.append("⚠️ ");
            sb.append(String.join(" | ", validation.warnings()));
            sb.append("\n");
        }
        sb.append(LINE).append("\n");
        sb.append(String.format("✅ <code>APPROVE %s</code>  |  ❌ <code>REJECT %s</code>%n",
            signal.getTradeId(), signal.getTradeId()));
        sb.append(String.format("⏰ %s  •  %s%n", expires, signal.getTradeId()));
        return sb.toString();
    }

    public String generateExecutionConfirmation(TradeSignal signal, String brokerOrderId) {
        if ("Mutual Fund".equals(signal.getAssetType())) {
            String mode = signal.getMfSignalMode() != null ? signal.getMfSignalMode() : "BUY";
            return String.format("✅ <b>MF APPROVED</b>  %s  •  %s%n₹%.0f @ NAV ₹%.4f  •  Act via your MF platform.",
                signal.getSymbol(), mode, signal.getCapitalAllocationInr(), signal.getEntryPrice());
        }
        return String.format("✅ <b>ORDER PLACED</b>  %s @ ₹%.2f  •  %d shares%nSL ₹%.2f  |  Target ₹%.2f  |  Order %s",
            signal.getSymbol(), signal.getEntryPrice(),
            (int)(signal.getCapitalAllocationInr() / signal.getEntryPrice()),
            signal.getStopLossPrice(), signal.getTargetPrice(), brokerOrderId);
    }

    public String generateRejectionAck(String tradeId, String reason) {
        return String.format("❌ Rejected  <code>%s</code>  —  %s", tradeId, reason);
    }

    public String generateExpiryNotification(String tradeId) {
        return String.format("⏰ Expired  <code>%s</code>  —  no response. No trade placed.", tradeId);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
