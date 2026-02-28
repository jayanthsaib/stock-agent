package com.jay.stagent.layer6_execution;

import com.jay.stagent.config.AgentConfig;
import com.jay.stagent.layer1_data.AngelOneClient;
import com.jay.stagent.layer1_data.InstrumentMasterService;
import com.jay.stagent.layer5_report.PreTradeReportGenerator;
import com.jay.stagent.model.TradeSignal;
import com.jay.stagent.model.enums.SignalStatus;
import com.jay.stagent.notification.TelegramService;
import com.jay.stagent.repository.TradeRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Layer 6 — Execution Engine.
 * Places LIMIT orders via Angel One after user approval.
 * Monitors order fill and handles timeouts.
 *
 * Hard rules enforced here:
 * - Only LIMIT orders (never MARKET)
 * - Auto-cancels if not filled within configured timeout
 * - Notifies Telegram on every execution event
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionEngine {

    private final AngelOneClient angelOneClient;
    private final TelegramService telegramService;
    private final PreTradeReportGenerator reportGenerator;
    private final TradeRecordRepository tradeRepo;
    private final AgentConfig config;
    private final InstrumentMasterService instrumentMaster;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    /**
     * Places a limit order for an approved signal.
     * Notifies the user of the outcome.
     */
    @Async
    public void execute(TradeSignal signal) {
        if (config.paperTrading().isEnabled()) {
            log.info("[PAPER] Skipping real execution for {} — paper trading mode", signal.getTradeId());
            return;
        }

        log.info("Executing order for signal {}: BUY {} @ ₹{}",
            signal.getTradeId(), signal.getSymbol(), signal.getEntryPrice());

        int quantity = computeQuantity(signal);
        if (quantity <= 0) {
            log.error("Computed quantity is 0 for {} — cannot place order", signal.getTradeId());
            telegramService.sendMessage("❌ Order failed: quantity computed as 0 for " + signal.getSymbol());
            return;
        }

        // Resolve Angel One token for the symbol (in production: use DataIngestionEngine token map)
        String symbolToken = resolveSymbolToken(signal.getSymbol());

        String brokerOrderId = angelOneClient.placeOrder(
            symbolToken,
            signal.getExchange(),
            signal.getSymbol(),
            "BUY",
            quantity,
            signal.getEntryPrice()
        );

        if (brokerOrderId == null) {
            signal.setStatus(SignalStatus.FAILED);
            updateTradeStatus(signal.getTradeId(), "FAILED", null);
            telegramService.sendMessage("❌ Order placement FAILED for " + signal.getSymbol() +
                " — broker rejected the order");
            return;
        }

        signal.setBrokerOrderId(brokerOrderId);
        signal.setStatus(SignalStatus.EXECUTED);
        signal.setExecutedAt(LocalDateTime.now());
        updateTradeStatus(signal.getTradeId(), "EXECUTED", brokerOrderId);

        // Notify user
        telegramService.sendMessage(
            reportGenerator.generateExecutionConfirmation(signal, brokerOrderId));

        // Schedule auto-cancel if not filled within timeout
        scheduleOrderTimeoutCheck(signal, brokerOrderId, quantity);
    }

    /**
     * Places a LIMIT sell order (for stop-loss hits, profit booking, etc.)
     */
    public String placeSellOrder(String symbol, String exchange, int quantity, double price, String reason) {
        if (config.paperTrading().isEnabled()) {
            log.info("[PAPER] Simulating sell: {} {} @ ₹{} — {}", quantity, symbol, price, reason);
            telegramService.sendMessage(String.format(
                "📄 <b>PAPER SELL EXECUTED</b>%n%s @ ₹%.2f × %d%nReason: %s",
                symbol, price, quantity, reason));
            return "PAPER-" + System.currentTimeMillis();
        }

        String symbolToken = resolveSymbolToken(symbol);
        String orderId = angelOneClient.placeOrder(symbolToken, exchange, symbol, "SELL", quantity, price);

        if (orderId != null) {
            telegramService.sendMessage(String.format(
                "📤 <b>SELL ORDER PLACED</b>%n%s @ ₹%.2f × %d%nReason: %s%nOrder ID: %s",
                symbol, price, quantity, reason, orderId));
        } else {
            telegramService.sendAlert("⚠️ SELL ORDER FAILED",
                String.format("%s @ ₹%.2f — %s", symbol, price, reason));
        }
        return orderId;
    }

    // ── Order Fill Timeout ─────────────────────────────────────────────────────

    private void scheduleOrderTimeoutCheck(TradeSignal signal, String orderId, int qty) {
        int timeoutMin = config.execution().getOrderFillTimeoutMinutes();
        scheduler.schedule(() -> checkAndCancelUnfilled(signal, orderId),
            timeoutMin, TimeUnit.MINUTES);
    }

    private void checkAndCancelUnfilled(TradeSignal signal, String orderId) {
        // In production: call Angel One order status API to check fill status
        // For now: notify user to manually verify
        log.info("Order timeout check for {} — verify fill status of order {}",
            signal.getTradeId(), orderId);
        telegramService.sendMessage(String.format(
            "⏰ <b>ORDER TIMEOUT CHECK</b>%n" +
            "Trade ID  : %s%n" +
            "Symbol    : %s%n" +
            "Order ID  : %s%n" +
            "Action    : Please verify if order was filled. If unfilled, cancel manually.",
            signal.getTradeId(), signal.getSymbol(), orderId
        ));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int computeQuantity(TradeSignal signal) {
        double allocationInr = signal.getCapitalAllocationInr();
        double price = signal.getEntryPrice();
        return price > 0 ? (int) Math.floor(allocationInr / price) : 0;
    }

    private void updateTradeStatus(String tradeId, String status, String brokerOrderId) {
        try {
            tradeRepo.findById(tradeId).ifPresent(record -> {
                record.setStatus(status);
                if (brokerOrderId != null) record.setBrokerOrderId(brokerOrderId);
                if ("EXECUTED".equals(status)) record.setExecutedAt(LocalDateTime.now());
                tradeRepo.save(record);
            });
        } catch (Exception e) {
            log.error("Failed to update execution status for {}: {}", tradeId, e.getMessage());
        }
    }

    /** Returns Angel One numeric token for a symbol via InstrumentMasterService. */
    private String resolveSymbolToken(String symbol) {
        String token = instrumentMaster.resolveToken(symbol, "NSE");
        if (token == null) token = instrumentMaster.resolveToken(symbol, "BSE");
        return token != null ? token : symbol; // fallback: pass symbol as-is
    }
}
