package com.jay.stagent.scheduler;

import com.jay.stagent.config.AgentConfig;
import com.jay.stagent.entity.TradeRecord;
import com.jay.stagent.layer1_data.DataIngestionEngine;
import com.jay.stagent.layer1_data.MarketCalendarService;
import com.jay.stagent.layer3_signal.SignalGenerator;
import com.jay.stagent.layer4_risk.RiskValidator;
import com.jay.stagent.layer6_execution.ApprovalGateway;
import com.jay.stagent.layer7_monitor.LearningEngine;
import com.jay.stagent.layer7_monitor.PortfolioMonitor;
import com.jay.stagent.model.TradeSignal;
import com.jay.stagent.notification.TelegramService;
import com.jay.stagent.repository.TradeRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Trading Scheduler — drives the entire agent lifecycle.
 *
 * Market hours (IST):
 *   Pre-market  : 08:45 — data refresh, macro check
 *   Market open : 09:15 — analysis pipeline, signal generation
 *   Intraday    : every 15 min (09:30–15:30) — position monitoring
 *   End of day  : 15:30 — daily summary, learning update
 *   Telegram    : every 2 seconds — poll for APPROVE/REJECT messages
 *   Monthly     : first of each month — learning engine review
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradingScheduler {

    private final DataIngestionEngine dataIngestionEngine;
    private final MarketCalendarService marketCalendar;
    private final SignalGenerator signalGenerator;
    private final RiskValidator riskValidator;
    private final ApprovalGateway approvalGateway;
    private final PortfolioMonitor portfolioMonitor;
    private final LearningEngine learningEngine;
    private final TelegramService telegramService;
    private final TradeRecordRepository tradeRepo;
    private final AgentConfig config;

    // ── Pre-Market (08:45 IST) ────────────────────────────────────────────────

    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void preMarket() {
        if (!marketCalendar.isMarketOpen()) {
            log.info("PRE-MARKET skipped — market holiday today");
            return;
        }
        log.info("=== PRE-MARKET (08:45) — Refreshing data ===");
        try {
            signalGenerator.resetDailySignals(); // reset per-day dedup before market open
            dataIngestionEngine.refreshAll();
            log.info("Pre-market data refresh complete");
        } catch (Exception e) {
            log.error("Pre-market refresh failed: {}", e.getMessage());
            telegramService.sendAlert("⚠️ PRE-MARKET REFRESH FAILED", e.getMessage());
        }
    }

    // ── Market Open Analysis (09:15 IST) ─────────────────────────────────────

    @Scheduled(cron = "0 15 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void marketOpenAnalysis() {
        if (!marketCalendar.isMarketOpen()) {
            log.info("MARKET OPEN skipped — market holiday today");
            return;
        }
        log.info("=== MARKET OPEN (09:15) — Running analysis pipeline ===");
        try {
            // Wait for pre-market refresh to finish if it is still running
            // (can happen if Phase 2 OHLCV fetch takes > 30 minutes or was triggered manually)
            if (dataIngestionEngine.isRefreshInProgress()) {
                log.warn("Data refresh still in progress at 09:15 — waiting up to 10 minutes");
                long deadline = System.currentTimeMillis() + 10 * 60_000L;
                while (dataIngestionEngine.isRefreshInProgress()
                        && System.currentTimeMillis() < deadline) {
                    Thread.sleep(15_000);
                }
                if (dataIngestionEngine.isRefreshInProgress()) {
                    log.error("Data refresh did not finish in time — proceeding with partial universe");
                    telegramService.sendAlert("⚠️ PARTIAL DATA WARNING",
                        "Pre-market refresh was still running at 09:15. Signals may be based on an incomplete universe.");
                }
            }

            // Fetch open positions for risk validation
            List<TradeRecord> openPositions = tradeRepo.findByStatus("EXECUTED");

            // Generate signals (runs all 4 analysis modules in parallel)
            List<TradeSignal> signals = signalGenerator.generateSignals();

            if (signals.isEmpty()) {
                log.info("No signals generated above confidence threshold");
                return;
            }

            // Validate and submit each signal
            for (TradeSignal signal : signals) {
                RiskValidator.ValidationResult validation =
                    riskValidator.validate(signal, openPositions);

                if (!validation.passed()) {
                    log.info("Signal for {} failed risk validation: {}",
                        signal.getSymbol(), validation.failures());
                    continue;
                }

                // Submit for approval (or auto-execute if threshold met and auto-mode on)
                if (config.execution().isAutoMode() &&
                    signal.getConfidenceScore().getComposite() >= config.signal().getAutoExecuteThreshold()) {
                    log.info("Auto-executing high-conviction signal for {} (score={:.0f}%)",
                        signal.getSymbol(), signal.getConfidenceScore().getComposite());
                    approvalGateway.submitForApproval(signal, validation);
                } else {
                    approvalGateway.submitForApproval(signal, validation);
                }
            }

        } catch (Exception e) {
            log.error("Market open analysis failed: {}", e.getMessage(), e);
            telegramService.sendAlert("⚠️ ANALYSIS PIPELINE ERROR", e.getMessage());
        }
    }

    // ── Intraday Position Monitoring (every 15 min, 09:30–15:30 IST) ─────────

    @Scheduled(cron = "0 0/15 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void intradayMonitor() {
        if (!marketCalendar.isMarketOpen()) return;
        log.debug("=== INTRADAY MONITOR ===");
        try {
            portfolioMonitor.monitorPositions();
            approvalGateway.expireTimedOutSignals();
        } catch (Exception e) {
            log.error("Intraday monitor failed: {}", e.getMessage());
        }
    }

    // ── End-of-Day (15:30 IST) ────────────────────────────────────────────────

    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void endOfDay() {
        if (!marketCalendar.isMarketOpen()) {
            log.info("END-OF-DAY skipped — market holiday today");
            return;
        }
        log.info("=== END OF DAY (15:30) ===");
        try {
            portfolioMonitor.sendDailySummary();
        } catch (Exception e) {
            log.error("End-of-day summary failed: {}", e.getMessage());
        }
    }

    // ── Intraday Signals (every 30 min, 10:00–14:30 IST) ─────────────────────
    // Refreshes live prices and generates new signals; dedup prevents re-alerting
    // the same stock that was already signaled at 09:15 or an earlier intraday run.

    @Scheduled(cron = "0 0/30 10-14 * * MON-FRI", zone = "Asia/Kolkata")
    public void intradaySignals() {
        if (!marketCalendar.isMarketOpen()) return;
        log.info("=== INTRADAY SIGNALS ({}) ===",
            java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")));
        try {
            dataIngestionEngine.refreshLivePrices(); // fast ~1–2 min Phase 1 refresh
            List<TradeRecord> openPositions = tradeRepo.findByStatus("EXECUTED");
            List<TradeSignal> signals = signalGenerator.generateSignals();

            int submitted = 0;
            for (TradeSignal signal : signals) {
                RiskValidator.ValidationResult validation =
                    riskValidator.validate(signal, openPositions);
                if (validation.passed()) {
                    approvalGateway.submitForApproval(signal, validation);
                    submitted++;
                }
            }
            log.info("Intraday run: {} new signals submitted to Telegram", submitted);
        } catch (Exception e) {
            log.error("Intraday signal generation failed: {}", e.getMessage(), e);
        }
    }

    // ── Telegram Polling (every 2 seconds) ───────────────────────────────────

    @Scheduled(fixedDelayString = "${agent.telegram-poll-ms:2000}")
    public void pollTelegram() {
        try {
            telegramService.pollForMessages();
        } catch (Exception e) {
            log.debug("Telegram poll error: {}", e.getMessage());
        }
    }

    // ── Monthly Learning Review (1st of each month at 07:00 IST) ─────────────

    @Scheduled(cron = "0 0 7 1 * *", zone = "Asia/Kolkata")
    public void monthlyReview() {
        log.info("=== MONTHLY LEARNING REVIEW ===");
        try {
            learningEngine.runMonthlyReview();
        } catch (Exception e) {
            log.error("Monthly review failed: {}", e.getMessage());
        }
    }
}
