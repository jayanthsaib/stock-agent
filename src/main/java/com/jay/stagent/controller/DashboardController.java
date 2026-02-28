package com.jay.stagent.controller;

import com.jay.stagent.config.AgentConfig;
import com.jay.stagent.entity.TradeRecord;
import com.jay.stagent.layer1_data.AngelOneClient;
import com.jay.stagent.layer3_signal.StockAnalysisService;
import com.jay.stagent.layer6_execution.ApprovalGateway;
import com.jay.stagent.layer7_monitor.LearningEngine;
import com.jay.stagent.model.StockAnalysisResult;
import com.jay.stagent.notification.TelegramService;
import com.jay.stagent.repository.TradeRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Serves the web dashboard UI.
 * Routes: /ui/dashboard, /ui/positions, /ui/signals, /ui/performance
 */
@Controller
@RequestMapping("/ui")
@RequiredArgsConstructor
public class DashboardController {

    private final AgentConfig config;
    private final AngelOneClient angelOneClient;
    private final ApprovalGateway approvalGateway;
    private final TradeRecordRepository tradeRepo;
    private final LearningEngine learningEngine;
    private final TelegramService telegramService;
    private final StockAnalysisService stockAnalysisService;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd MMM HH:mm");

    // ── / redirect ─────────────────────────────────────────────────────────────

    @GetMapping({"", "/"})
    public String root() {
        return "redirect:/ui/dashboard";
    }

    // ── Dashboard ──────────────────────────────────────────────────────────────

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        List<TradeRecord> open     = tradeRepo.findByStatus("EXECUTED");
        List<TradeRecord> pending  = tradeRepo.findByStatus("PENDING_APPROVAL");
        List<TradeRecord> recent   = tradeRepo.findByGeneratedAtAfter(LocalDateTime.now().minusDays(7));
        List<TradeRecord> allClosed = tradeRepo.findAllClosed();

        long wins = allClosed.stream().filter(TradeRecord::isTargetHit).count();
        double winRate = allClosed.isEmpty() ? 0 : (double) wins / allClosed.size() * 100;
        double totalPnl = allClosed.stream()
            .filter(t -> t.getRealisedPnlInr() != null)
            .mapToDouble(TradeRecord::getRealisedPnlInr).sum();

        model.addAttribute("mode",          config.paperTrading().isEnabled() ? "PAPER TRADING" : "LIVE");
        model.addAttribute("modeBadge",     config.paperTrading().isEnabled() ? "bg-warning text-dark" : "bg-danger");
        model.addAttribute("autoMode",      config.execution().isAutoMode());
        model.addAttribute("brokerAuth",    angelOneClient.isAuthenticated());
        model.addAttribute("telegramOk",    telegramService.testConnection());
        model.addAttribute("pendingCount",  pending.size());
        model.addAttribute("openCount",     open.size());
        model.addAttribute("watchlistSize", config.watchlist().size());
        model.addAttribute("winRate",       String.format("%.1f", winRate));
        model.addAttribute("totalPnl",      String.format("%.0f", totalPnl));
        model.addAttribute("recentSignals", recent.stream().limit(8).toList());
        model.addAttribute("fmt",           FMT);
        model.addAttribute("activePage",    "dashboard");
        return "dashboard";
    }

    // ── Positions ──────────────────────────────────────────────────────────────

    @GetMapping("/positions")
    public String positions(Model model) {
        List<TradeRecord> positions = tradeRepo.findByStatus("EXECUTED");
        model.addAttribute("positions",  positions);
        model.addAttribute("fmt",        FMT);
        model.addAttribute("activePage", "positions");
        return "positions";
    }

    // ── Signals ────────────────────────────────────────────────────────────────

    @GetMapping("/signals")
    public String signals(@RequestParam(defaultValue = "30") int days, Model model) {
        model.addAttribute("pending",    tradeRepo.findByStatus("PENDING_APPROVAL"));
        model.addAttribute("history",    tradeRepo.findByGeneratedAtAfter(LocalDateTime.now().minusDays(days)));
        model.addAttribute("days",       days);
        model.addAttribute("fmt",        FMT);
        model.addAttribute("activePage", "signals");
        return "signals";
    }

    // ── Analyse ────────────────────────────────────────────────────────────────

    @GetMapping("/analyse")
    public String analyse(@RequestParam(required = false) String symbol, Model model) {
        model.addAttribute("activePage", "analyse");
        model.addAttribute("symbol", symbol != null ? symbol.toUpperCase().trim() : "");
        if (symbol != null && !symbol.isBlank()) {
            StockAnalysisResult result = stockAnalysisService.analyse(symbol);
            model.addAttribute("result", result);
        }
        return "analyse";
    }

    // ── Performance ────────────────────────────────────────────────────────────

    @GetMapping("/performance")
    public String performance(Model model) {
        List<TradeRecord> closed = tradeRepo.findAllClosed();
        long wins   = closed.stream().filter(TradeRecord::isTargetHit).count();
        long losses = closed.size() - wins;
        double winRate  = closed.isEmpty() ? 0 : (double) wins / closed.size() * 100;
        double totalPnl = closed.stream()
            .filter(t -> t.getRealisedPnlInr() != null)
            .mapToDouble(TradeRecord::getRealisedPnlInr).sum();
        double avgPnl = closed.isEmpty() ? 0 : totalPnl / closed.size();

        // Chart data — cumulative P&L by trade date
        StringBuilder chartLabels = new StringBuilder("[");
        StringBuilder chartData   = new StringBuilder("[");
        double cumulative = 0;
        for (TradeRecord t : closed) {
            if (t.getRealisedPnlInr() != null) {
                cumulative += t.getRealisedPnlInr();
                chartLabels.append("'").append(t.getClosedAt().format(DateTimeFormatter.ofPattern("dd MMM"))).append("',");
                chartData.append(String.format("%.2f", cumulative)).append(",");
            }
        }
        if (chartLabels.length() > 1) {
            chartLabels.setLength(chartLabels.length() - 1);
            chartData.setLength(chartData.length() - 1);
        }
        chartLabels.append("]");
        chartData.append("]");

        model.addAttribute("totalTrades",    closed.size());
        model.addAttribute("wins",           wins);
        model.addAttribute("losses",         losses);
        model.addAttribute("winRate",        String.format("%.1f", winRate));
        model.addAttribute("totalPnl",       String.format("%.0f", totalPnl));
        model.addAttribute("avgPnl",         String.format("%.0f", avgPnl));
        model.addAttribute("closedTrades",   closed.stream().limit(50).toList());
        model.addAttribute("chartLabels",    chartLabels.toString());
        model.addAttribute("chartData",      chartData.toString());
        model.addAttribute("calibration",    learningEngine.runConfidenceCalibration(closed));
        model.addAttribute("sectorAnalysis", learningEngine.runSectorAnalysis(closed));
        model.addAttribute("fmt",            FMT);
        model.addAttribute("activePage",     "performance");
        return "performance";
    }
}
