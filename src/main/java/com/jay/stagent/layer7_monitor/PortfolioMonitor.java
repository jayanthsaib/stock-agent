package com.jay.stagent.layer7_monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.jay.stagent.config.AgentConfig;
import com.jay.stagent.entity.TradeRecord;
import com.jay.stagent.layer1_data.AngelOneClient;
import com.jay.stagent.layer1_data.InstrumentMasterService;
import com.jay.stagent.layer6_execution.ExecutionEngine;
import com.jay.stagent.notification.TelegramService;
import com.jay.stagent.repository.TradeRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Layer 7 — Portfolio Monitor.
 * Monitors all open positions every 15 minutes during market hours.
 *
 * Responsibilities:
 * - Stop-loss breach detection → auto-sell (no approval required for exits)
 * - Trailing stop management (only moves UP)
 * - Target hit → send profit-booking recommendation
 * - Max drawdown per trade → auto-sell
 * - Portfolio-level drawdown check → suppress new buys
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioMonitor {

    private final AngelOneClient angelOneClient;
    private final InstrumentMasterService instrumentMaster;
    private final TradeRecordRepository tradeRepo;
    private final ExecutionEngine executionEngine;
    private final TelegramService telegramService;
    private final AgentConfig config;

    /**
     * Main monitoring loop — called every 15 minutes by TradingScheduler.
     * Checks all open positions against current prices.
     */
    public void monitorPositions() {
        List<TradeRecord> openPositions = tradeRepo.findByStatus("EXECUTED");
        if (openPositions.isEmpty()) {
            log.debug("No open positions to monitor");
            return;
        }

        log.info("Monitoring {} open positions", openPositions.size());

        for (TradeRecord position : openPositions) {
            try {
                double currentPrice = fetchCurrentPrice(position.getSymbol());
                if (currentPrice <= 0) {
                    log.warn("Could not fetch price for {} — skipping", position.getSymbol());
                    continue;
                }

                checkStopLoss(position, currentPrice);
                checkTarget(position, currentPrice);
                updateTrailingStop(position, currentPrice);

            } catch (Exception e) {
                log.error("Error monitoring position {}: {}", position.getSymbol(), e.getMessage());
            }
        }
    }

    // ── Stop-Loss Check ────────────────────────────────────────────────────────

    private void checkStopLoss(TradeRecord position, double currentPrice) {
        if (currentPrice <= position.getStopLossPrice()) {
            log.warn("STOP-LOSS HIT for {} — current ₹{} <= SL ₹{}",
                position.getSymbol(), currentPrice, position.getStopLossPrice());

            // Auto-sell — no approval required for exits
            int quantity = computeQuantity(position);
            String orderId = executionEngine.placeSellOrder(
                position.getSymbol(),
                "NSE",
                quantity,
                currentPrice,
                "STOP-LOSS HIT"
            );

            if (orderId != null) {
                closePosition(position, currentPrice, "STOP_LOSS_HIT");
                telegramService.sendAlert("🔴 STOP-LOSS TRIGGERED",
                    String.format("%s sold @ ₹%.2f | P&L: ₹%.0f",
                        position.getSymbol(), currentPrice,
                        (currentPrice - position.getEntryPrice()) * quantity));
            }
        }
    }

    // ── Target Check ───────────────────────────────────────────────────────────

    private void checkTarget(TradeRecord position, double currentPrice) {
        if (currentPrice >= position.getTargetPrice()) {
            log.info("TARGET REACHED for {} — current ₹{} >= target ₹{}",
                position.getSymbol(), currentPrice, position.getTargetPrice());

            int quantity = computeQuantity(position);
            double gain = (currentPrice - position.getEntryPrice()) * quantity;

            // At 50% of target achieved, recommend partial exit
            double halfTarget = position.getEntryPrice() +
                (position.getTargetPrice() - position.getEntryPrice()) * 0.5;
            if (currentPrice >= halfTarget && currentPrice < position.getTargetPrice()) {
                telegramService.sendAlert("💰 PARTIAL PROFIT OPPORTUNITY",
                    String.format("%s at 50%% of target.%n" +
                        "Consider selling 50%% of position.%n" +
                        "Current: ₹%.2f | Target: ₹%.2f",
                        position.getSymbol(), currentPrice, position.getTargetPrice()));
                return;
            }

            // Full target hit — send recommendation (approval required for profit booking)
            telegramService.sendAlert("🎯 TARGET HIT",
                String.format("%s @ ₹%.2f — Target ₹%.2f reached!%n" +
                    "Estimated gain: ₹%.0f%n" +
                    "Reply APPROVE %s to book profits.",
                    position.getSymbol(), currentPrice, position.getTargetPrice(),
                    gain, position.getTradeId()));
        }

        // Max single-trade drawdown check
        double drawdownPct = (position.getEntryPrice() - currentPrice) / position.getEntryPrice() * 100;
        if (drawdownPct >= config.risk().getMaxSingleTradeDrawdownPct()) {
            log.warn("MAX DRAWDOWN exceeded for {} — drawdown {:.1f}%",
                position.getSymbol(), drawdownPct);
            int quantity = computeQuantity(position);
            executionEngine.placeSellOrder(
                position.getSymbol(), "NSE", quantity, currentPrice,
                String.format("MAX DRAWDOWN %.1f%% exceeded", drawdownPct));
            closePosition(position, currentPrice, "MAX_DRAWDOWN");
        }
    }

    // ── Trailing Stop ──────────────────────────────────────────────────────────

    private void updateTrailingStop(TradeRecord position, double currentPrice) {
        AgentConfig.Risk risk = config.risk();
        double profitPct = (currentPrice - position.getEntryPrice()) / position.getEntryPrice() * 100;

        if (profitPct < risk.getTrailingStopActivatePct()) return; // Trailing stop not yet activated

        // New trailing stop: keep same % distance from current price as original SL from entry
        double originalSlDistance = (position.getEntryPrice() - position.getStopLossPrice());
        double newSl = currentPrice - originalSlDistance;

        // Trailing stop can ONLY move UP — never down
        if (newSl > position.getStopLossPrice()) {
            double oldSl = position.getStopLossPrice();
            position.setStopLossPrice(newSl);
            tradeRepo.save(position);
            log.info("Trailing stop updated for {}: ₹{} → ₹{}",
                position.getSymbol(), oldSl, newSl);

            telegramService.sendMessage(String.format(
                "📈 <b>TRAILING STOP UPDATED</b>%n" +
                "%s — P&L: +%.1f%%%nStop-loss raised: ₹%.2f → ₹%.2f",
                position.getSymbol(), profitPct, oldSl, newSl));
        }
    }

    // ── End-of-Day Tasks ───────────────────────────────────────────────────────

    /**
     * Generates and sends the daily P&L summary.
     * Called by TradingScheduler at 3:30 PM.
     */
    public void sendDailySummary() {
        List<TradeRecord> openPositions = tradeRepo.findByStatus("EXECUTED");
        List<TradeRecord> closedToday   = tradeRepo.findByGeneratedAtAfter(
            LocalDateTime.now().toLocalDate().atStartOfDay());

        double todayPnl = closedToday.stream()
            .filter(t -> t.getRealisedPnlInr() != null)
            .mapToDouble(TradeRecord::getRealisedPnlInr)
            .sum();

        telegramService.sendAlert("📊 END-OF-DAY SUMMARY", String.format(
            "Open positions : %d%n" +
            "Closed today   : %d%n" +
            "Today's P&L    : %s₹%.0f%n" +
            "Mode           : %s",
            openPositions.size(),
            closedToday.size(),
            todayPnl >= 0 ? "+" : "",
            todayPnl,
            config.paperTrading().isEnabled() ? "PAPER TRADING" : "LIVE"
        ));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double fetchCurrentPrice(String symbol) {
        // Resolve token and fetch live LTP via getQuote — prefer NSE, fall back to BSE
        try {
            String token = instrumentMaster.resolveToken(symbol, "NSE");
            String exchange = "NSE";
            if (token == null) {
                token = instrumentMaster.resolveToken(symbol, "BSE");
                exchange = "BSE";
            }
            if (token == null) {
                log.warn("No instrument token for {} — cannot fetch live price", symbol);
                return 0;
            }
            JsonNode data = angelOneClient.getQuote(exchange, List.of(token));
            JsonNode fetched = data.path("fetched");
            if (fetched.isArray() && !fetched.isEmpty()) {
                return fetched.get(0).path("ltp").asDouble(0);
            }
        } catch (Exception e) {
            log.warn("Could not fetch live price for {}: {}", symbol, e.getMessage());
        }
        return 0;
    }

    private int computeQuantity(TradeRecord position) {
        return position.getEntryPrice() > 0
            ? (int) Math.floor(position.getCapitalAllocationInr() / position.getEntryPrice())
            : 0;
    }

    private void closePosition(TradeRecord position, double exitPrice, String reason) {
        double qty = computeQuantity(position);
        double pnl = (exitPrice - position.getEntryPrice()) * qty;
        double pnlPct = (exitPrice - position.getEntryPrice()) / position.getEntryPrice() * 100;

        position.setStatus("CLOSED");
        position.setExitPrice(exitPrice);
        position.setRealisedPnlInr(pnl);
        position.setRealisedPnlPct(pnlPct);
        position.setExitReason(reason);
        position.setClosedAt(LocalDateTime.now());
        position.setTargetHit("TARGET_HIT".equals(reason));
        tradeRepo.save(position);
        log.info("Position closed: {} @ ₹{} | P&L: ₹{} ({:.1f}%) | Reason: {}",
            position.getSymbol(), exitPrice, pnl, pnlPct, reason);
    }
}
