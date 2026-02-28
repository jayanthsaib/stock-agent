package com.jay.stagent.layer6_execution;

import com.jay.stagent.config.AgentConfig;
import com.jay.stagent.entity.TradeRecord;
import com.jay.stagent.layer5_report.PreTradeReportGenerator;
import com.jay.stagent.model.TradeSignal;
import com.jay.stagent.model.enums.SignalStatus;
import com.jay.stagent.notification.TelegramService;
import com.jay.stagent.repository.TradeRecordRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer 6 — Approval Gateway.
 * Manages the approval lifecycle for trade signals:
 *   1. Sends the Pre-Trade Report to Telegram
 *   2. Waits for APPROVE/REJECT reply from the user
 *   3. Routes approved signals to ExecutionEngine
 *   4. Handles timeouts — auto-expires unanswered signals
 *
 * Message format: "APPROVE TRD-XXXXXXXX" or "REJECT TRD-XXXXXXXX [reason]"
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalGateway {

    private final TelegramService telegramService;
    private final ExecutionEngine executionEngine;
    private final PreTradeReportGenerator reportGenerator;
    private final TradeRecordRepository tradeRepo;
    private final AgentConfig config;

    // Active pending signals: tradeId → signal
    private final Map<String, TradeSignal> pendingSignals = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // Register Telegram message handler for APPROVE/REJECT commands
        telegramService.addMessageHandler(this::handleTelegramMessage);
        log.info("ApprovalGateway initialized — listening for APPROVE/REJECT commands");
    }

    // ── Submitting Signals for Approval ───────────────────────────────────────

    /**
     * Sends a trade signal for approval via Telegram.
     * Stores it in pending state until approved, rejected, or expired.
     */
    public void submitForApproval(TradeSignal signal,
                                   com.jay.stagent.layer4_risk.RiskValidator.ValidationResult validation) {
        String report = reportGenerator.generate(signal, validation);
        boolean sent = telegramService.sendMessage(report);

        if (!sent) {
            log.warn("Could not send Telegram report for {} — signal discarded", signal.getTradeId());
            return;
        }

        signal.setStatus(SignalStatus.PENDING_APPROVAL);
        pendingSignals.put(signal.getTradeId(), signal);

        // Persist to DB
        saveTrade(signal, "PENDING_APPROVAL");

        log.info("Signal {} submitted for approval via Telegram. Expires at {}",
            signal.getTradeId(), signal.getExpiresAt());
    }

    // ── Expiry Check ──────────────────────────────────────────────────────────

    /**
     * Called by TradingScheduler to expire timed-out signals.
     */
    public void expireTimedOutSignals() {
        LocalDateTime now = LocalDateTime.now();
        pendingSignals.values().stream()
            .filter(s -> s.getExpiresAt() != null && s.getExpiresAt().isBefore(now))
            .toList()
            .forEach(signal -> {
                log.info("Signal {} expired — no response received", signal.getTradeId());
                signal.setStatus(SignalStatus.EXPIRED);
                pendingSignals.remove(signal.getTradeId());
                updateTradeStatus(signal.getTradeId(), "EXPIRED", null);
                telegramService.sendMessage(
                    reportGenerator.generateExpiryNotification(signal.getTradeId()));
            });
    }

    // ── Telegram Message Handler ───────────────────────────────────────────────

    /**
     * Processes incoming Telegram messages for APPROVE/REJECT commands.
     * Format: "APPROVE TRD-XXXXXXXX" or "REJECT TRD-XXXXXXXX [optional reason]"
     */
    private void handleTelegramMessage(TelegramService.TelegramMessage msg) {
        String text = msg.text().trim().toUpperCase();

        if (text.startsWith("APPROVE ")) {
            String tradeId = extractTradeId(msg.text(), "APPROVE");
            if (tradeId != null) handleApproval(tradeId);

        } else if (text.startsWith("REJECT ")) {
            String[] parts = msg.text().trim().split("\\s+", 3);
            String tradeId = parts.length >= 2 ? parts[1].toUpperCase() : null;
            String reason  = parts.length >= 3 ? parts[2] : "User rejected";
            if (tradeId != null) handleRejection(tradeId, reason);

        } else if (text.equals("STATUS")) {
            telegramService.sendMessage(buildStatusMessage());

        } else if (text.equals("POSITIONS")) {
            telegramService.sendMessage(buildPositionsMessage());
        }
    }

    private void handleApproval(String tradeId) {
        TradeSignal signal = pendingSignals.remove(tradeId);
        if (signal == null) {
            telegramService.sendMessage("❓ Unknown or already processed trade ID: " + tradeId);
            return;
        }

        log.info("Signal {} APPROVED by user", tradeId);
        signal.setStatus(SignalStatus.APPROVED);
        updateTradeStatus(tradeId, "APPROVED", null);

        if (config.paperTrading().isEnabled()) {
            // Paper trading: simulate execution without real order
            log.info("[PAPER] Simulating execution for {}", tradeId);
            telegramService.sendMessage(String.format(
                "📄 <b>PAPER TRADE EXECUTED</b>%n" +
                "Trade ID : %s%n" +
                "Symbol   : %s @ ₹%.2f%n" +
                "No real order placed (paper trading mode).",
                signal.getTradeId(), signal.getSymbol(), signal.getEntryPrice()));
            updateTradeStatus(tradeId, "EXECUTED", "PAPER");
        } else {
            // Real execution
            executionEngine.execute(signal);
        }
    }

    private void handleRejection(String tradeId, String reason) {
        TradeSignal signal = pendingSignals.remove(tradeId);
        if (signal == null) {
            telegramService.sendMessage("❓ Unknown or already processed trade ID: " + tradeId);
            return;
        }

        log.info("Signal {} REJECTED by user. Reason: {}", tradeId, reason);
        signal.setStatus(SignalStatus.REJECTED);
        signal.setRejectionReason(reason);
        updateTradeStatus(tradeId, "REJECTED", reason);
        telegramService.sendMessage(reportGenerator.generateRejectionAck(tradeId, reason));
    }

    // ── DB Helpers ─────────────────────────────────────────────────────────────

    private void saveTrade(TradeSignal signal, String status) {
        try {
            var cs = signal.getConfidenceScore();
            TradeRecord record = TradeRecord.builder()
                .tradeId(signal.getTradeId())
                .symbol(signal.getSymbol())
                .exchange(signal.getExchange())
                .sector(signal.getSector())
                .signalType(signal.getSignalType().name())
                .status(status)
                .entryPrice(signal.getEntryPrice())
                .targetPrice(signal.getTargetPrice())
                .stopLossPrice(signal.getStopLossPrice())
                .riskRewardRatio(signal.getRiskRewardRatio())
                .capitalAllocationInr(signal.getCapitalAllocationInr())
                .confidenceScore(cs.getComposite())
                .fundamentalScore(cs.getFundamentalScore())
                .technicalScore(cs.getTechnicalScore())
                .macroScore(cs.getMacroScore())
                .riskRewardScore(cs.getRiskRewardScore())
                .generatedAt(signal.getGeneratedAt())
                .expiresAt(signal.getExpiresAt())
                .fundamentalSummary(truncate(signal.getFundamentalSummary(), 500))
                .technicalSummary(truncate(signal.getTechnicalSummary(), 500))
                .macroContext(truncate(signal.getMacroContext(), 300))
                .build();
            tradeRepo.save(record);
        } catch (Exception e) {
            log.error("Failed to save trade record for {}: {}", signal.getTradeId(), e.getMessage());
        }
    }

    private void updateTradeStatus(String tradeId, String status, String reason) {
        try {
            tradeRepo.findById(tradeId).ifPresent(record -> {
                record.setStatus(status);
                if (reason != null) record.setRejectionReason(reason);
                if ("APPROVED".equals(status)) record.setApprovedAt(LocalDateTime.now());
                tradeRepo.save(record);
            });
        } catch (Exception e) {
            log.error("Failed to update trade status for {}: {}", tradeId, e.getMessage());
        }
    }

    // ── Status Messages ────────────────────────────────────────────────────────

    private String buildStatusMessage() {
        return String.format(
            "<b>🤖 Agent Status</b>%n" +
            "Mode      : %s%n" +
            "Pending   : %d signals awaiting approval%n" +
            "Auto-mode : %s",
            config.paperTrading().isEnabled() ? "📄 PAPER TRADING" : "💰 LIVE",
            pendingSignals.size(),
            config.execution().isAutoMode() ? "ENABLED" : "DISABLED"
        );
    }

    private String buildPositionsMessage() {
        var openTrades = tradeRepo.findByStatus("EXECUTED");
        if (openTrades.isEmpty()) return "📊 No open positions.";

        StringBuilder sb = new StringBuilder("<b>📊 Open Positions</b>\n");
        openTrades.forEach(t -> sb.append(String.format(
            "• %s — Entry: ₹%.2f | SL: ₹%.2f | Target: ₹%.2f%n",
            t.getSymbol(), t.getEntryPrice(), t.getStopLossPrice(), t.getTargetPrice()
        )));
        return sb.toString();
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private String extractTradeId(String text, String command) {
        String[] parts = text.trim().split("\\s+");
        return parts.length >= 2 ? parts[1].toUpperCase() : null;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    public int getPendingSignalCount() {
        return pendingSignals.size();
    }
}
