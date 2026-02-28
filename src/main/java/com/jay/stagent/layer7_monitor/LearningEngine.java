package com.jay.stagent.layer7_monitor;

import com.jay.stagent.entity.TradeRecord;
import com.jay.stagent.notification.TelegramService;
import com.jay.stagent.repository.TradeRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Layer 7 — Learning Engine.
 * Tracks trade outcomes, calibrates the confidence scoring model,
 * and generates performance insights for the monthly/quarterly review.
 *
 * This engine does NOT modify live trading rules automatically.
 * It produces reports and flags patterns that require manual parameter review.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningEngine {

    private final TradeRecordRepository tradeRepo;
    private final TelegramService telegramService;

    // ── Monthly Review ─────────────────────────────────────────────────────────

    /**
     * Runs the monthly learning review.
     * Called by TradingScheduler on the first trading day of each month.
     */
    public void runMonthlyReview() {
        log.info("LearningEngine: running monthly review");
        LocalDateTime oneMonthAgo = LocalDateTime.now().minusMonths(1);

        List<TradeRecord> closedTrades = tradeRepo.findAllClosed().stream()
            .filter(t -> t.getClosedAt() != null && t.getClosedAt().isAfter(oneMonthAgo))
            .toList();

        if (closedTrades.isEmpty()) {
            log.info("No closed trades in the past month — skipping review");
            return;
        }

        PerformanceStats stats = computeStats(closedTrades);
        String report = buildMonthlyReport(stats, closedTrades);

        log.info("Monthly review complete: {}", stats);
        telegramService.sendAlert("📈 MONTHLY PERFORMANCE REVIEW", report);
    }

    // ── Confidence Calibration ─────────────────────────────────────────────────

    /**
     * Checks if the confidence score is well-calibrated:
     * High-confidence signals should have higher win rates.
     * Returns a calibration report string.
     */
    public String runConfidenceCalibration(List<TradeRecord> closedTrades) {
        if (closedTrades.size() < 10) return "Insufficient data for calibration (need 10+ closed trades)";

        Map<String, List<TradeRecord>> byBracket = closedTrades.stream()
            .collect(Collectors.groupingBy(t -> {
                double score = t.getConfidenceScore();
                if (score >= 85) return "85-100 (High)";
                if (score >= 70) return "70-84 (Strong)";
                return "60-69 (Moderate)";
            }));

        StringBuilder sb = new StringBuilder("Confidence Calibration:\n");
        byBracket.forEach((bracket, trades) -> {
            long wins = trades.stream().filter(TradeRecord::isTargetHit).count();
            double winRate = (double) wins / trades.size() * 100;
            sb.append(String.format("  %s: %.0f%% win rate (%d/%d trades)%n",
                bracket, winRate, wins, trades.size()));
        });

        return sb.toString();
    }

    // ── Sector Analysis ───────────────────────────────────────────────────────

    public String runSectorAnalysis(List<TradeRecord> closedTrades) {
        Map<String, List<TradeRecord>> bySector = closedTrades.stream()
            .filter(t -> t.getSector() != null && !t.getSector().isBlank())
            .collect(Collectors.groupingBy(TradeRecord::getSector));

        StringBuilder sb = new StringBuilder("Sector Performance:\n");
        bySector.entrySet().stream()
            .sorted((a, b) -> {
                double aWin = winRate(a.getValue());
                double bWin = winRate(b.getValue());
                return Double.compare(bWin, aWin); // Descending
            })
            .forEach(entry -> {
                double wr = winRate(entry.getValue());
                double avgPnl = entry.getValue().stream()
                    .filter(t -> t.getRealisedPnlPct() != null)
                    .mapToDouble(TradeRecord::getRealisedPnlPct)
                    .average().orElse(0);
                sb.append(String.format("  %s: %.0f%% win rate | avg P&L %.1f%% (%d trades)%n",
                    entry.getKey(), wr, avgPnl, entry.getValue().size()));
            });

        return sb.toString();
    }

    // ── Rejection Analysis ────────────────────────────────────────────────────

    /**
     * Analyses trades that were REJECTED by the user.
     * Did the rejected signals work out? If yes, reconsider approval criteria.
     */
    public String runRejectionAnalysis() {
        List<TradeRecord> rejected = tradeRepo.findByStatus("REJECTED");
        if (rejected.isEmpty()) return "No rejected signals to analyse.";

        StringBuilder sb = new StringBuilder(String.format(
            "Rejected Signal Analysis (%d signals):%n", rejected.size()));

        Map<String, Long> byReason = rejected.stream()
            .filter(t -> t.getRejectionReason() != null)
            .collect(Collectors.groupingBy(TradeRecord::getRejectionReason, Collectors.counting()));

        sb.append("Top rejection reasons:\n");
        byReason.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(5)
            .forEach(e -> sb.append(String.format("  • %s (%d times)%n", e.getKey(), e.getValue())));

        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private record PerformanceStats(
        int totalTrades,
        int wins,
        int losses,
        double winRate,
        double avgWinPct,
        double avgLossPct,
        double winLossRatio,
        double totalPnlInr
    ) {}

    private PerformanceStats computeStats(List<TradeRecord> trades) {
        int wins   = (int) trades.stream().filter(TradeRecord::isTargetHit).count();
        int losses = trades.size() - wins;
        double winRate = trades.isEmpty() ? 0 : (double) wins / trades.size() * 100;

        double avgWin = trades.stream()
            .filter(TradeRecord::isTargetHit)
            .filter(t -> t.getRealisedPnlPct() != null)
            .mapToDouble(TradeRecord::getRealisedPnlPct)
            .average().orElse(0);

        double avgLoss = trades.stream()
            .filter(t -> !t.isTargetHit())
            .filter(t -> t.getRealisedPnlPct() != null)
            .mapToDouble(TradeRecord::getRealisedPnlPct)
            .average().orElse(0);

        double totalPnl = trades.stream()
            .filter(t -> t.getRealisedPnlInr() != null)
            .mapToDouble(TradeRecord::getRealisedPnlInr)
            .sum();

        double wlRatio = avgLoss != 0 ? Math.abs(avgWin / avgLoss) : 0;

        return new PerformanceStats(trades.size(), wins, losses, winRate,
            avgWin, avgLoss, wlRatio, totalPnl);
    }

    private String buildMonthlyReport(PerformanceStats stats, List<TradeRecord> trades) {
        String calibration = runConfidenceCalibration(trades);
        String sectorAnalysis = runSectorAnalysis(trades);

        return String.format(
            "Period: Last 30 days%n" +
            "Total trades  : %d%n" +
            "Win / Loss    : %d / %d (%.0f%% win rate)%n" +
            "Avg Win       : +%.1f%%%n" +
            "Avg Loss      : %.1f%%%n" +
            "Win/Loss Ratio: %.2f%n" +
            "Total P&L     : %s₹%.0f%n%n" +
            "%s%n%s",
            stats.totalTrades(), stats.wins(), stats.losses(), stats.winRate(),
            stats.avgWinPct(), stats.avgLossPct(), stats.winLossRatio(),
            stats.totalPnlInr() >= 0 ? "+" : "", stats.totalPnlInr(),
            calibration, sectorAnalysis
        );
    }

    private double winRate(List<TradeRecord> trades) {
        if (trades.isEmpty()) return 0;
        return (double) trades.stream().filter(TradeRecord::isTargetHit).count() / trades.size() * 100;
    }
}
