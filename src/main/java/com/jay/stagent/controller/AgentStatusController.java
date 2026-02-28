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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * REST API — Agent Status & Monitoring Dashboard.
 * Provides endpoints for checking agent state, open positions, and trade history.
 *
 * Endpoints:
 *   GET  /api/status           — Agent health and configuration summary
 *   GET  /api/positions        — Current open positions
 *   GET  /api/signals/pending  — Signals awaiting approval
 *   GET  /api/signals/history  — Recent trade history
 *   GET  /api/performance      — Win rate and P&L stats
 *   POST /api/telegram/test    — Test Telegram bot connectivity
 *   POST /api/broker/login     — Trigger Angel One session refresh
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AgentStatusController {

    private final AgentConfig config;
    private final AngelOneClient angelOneClient;
    private final ApprovalGateway approvalGateway;
    private final TradeRecordRepository tradeRepo;
    private final LearningEngine learningEngine;
    private final TelegramService telegramService;
    private final StockAnalysisService stockAnalysisService;

    // ── GET /api/status ────────────────────────────────────────────────────────

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
            "status", "RUNNING",
            "timestamp", LocalDateTime.now().toString(),
            "mode", config.paperTrading().isEnabled() ? "PAPER_TRADING" : "LIVE",
            "autoMode", config.execution().isAutoMode(),
            "brokerAuthenticated", angelOneClient.isAuthenticated(),
            "telegramConnected", telegramService.testConnection(),
            "pendingSignals", approvalGateway.getPendingSignalCount(),
            "openPositions", tradeRepo.findByStatus("EXECUTED").size(),
            "watchlistSize", config.watchlist().size()
        ));
    }

    // ── GET /api/positions ─────────────────────────────────────────────────────

    @GetMapping("/positions")
    public ResponseEntity<List<TradeRecord>> openPositions() {
        return ResponseEntity.ok(tradeRepo.findByStatus("EXECUTED"));
    }

    // ── GET /api/signals/pending ───────────────────────────────────────────────

    @GetMapping("/signals/pending")
    public ResponseEntity<List<TradeRecord>> pendingSignals() {
        return ResponseEntity.ok(tradeRepo.findByStatus("PENDING_APPROVAL"));
    }

    // ── GET /api/signals/history ───────────────────────────────────────────────

    @GetMapping("/signals/history")
    public ResponseEntity<List<TradeRecord>> signalHistory(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(
            tradeRepo.findByGeneratedAtAfter(LocalDateTime.now().minusDays(days)));
    }

    // ── GET /api/performance ───────────────────────────────────────────────────

    @GetMapping("/performance")
    public ResponseEntity<Map<String, Object>> performance() {
        List<TradeRecord> allClosed = tradeRepo.findAllClosed();
        long wins = allClosed.stream().filter(TradeRecord::isTargetHit).count();
        double winRate = allClosed.isEmpty() ? 0 : (double) wins / allClosed.size() * 100;
        double totalPnl = allClosed.stream()
            .filter(t -> t.getRealisedPnlInr() != null)
            .mapToDouble(TradeRecord::getRealisedPnlInr).sum();

        String calibration = learningEngine.runConfidenceCalibration(allClosed);
        String sectorAnalysis = learningEngine.runSectorAnalysis(allClosed);

        return ResponseEntity.ok(Map.of(
            "totalClosedTrades", allClosed.size(),
            "wins", wins,
            "losses", allClosed.size() - wins,
            "winRatePct", winRate,
            "totalRealizedPnlInr", totalPnl,
            "confidenceCalibration", calibration,
            "sectorAnalysis", sectorAnalysis,
            "rejectionAnalysis", learningEngine.runRejectionAnalysis()
        ));
    }

    // ── POST /api/telegram/test ────────────────────────────────────────────────

    @PostMapping("/telegram/test")
    public ResponseEntity<Map<String, Object>> testTelegram() {
        boolean connected = telegramService.testConnection();
        if (connected) {
            telegramService.sendMessage("✅ Agent test message — Telegram connected successfully!");
        }
        return ResponseEntity.ok(Map.of("connected", connected));
    }

    // ── POST /api/broker/login ─────────────────────────────────────────────────

    @PostMapping("/broker/login")
    public ResponseEntity<Map<String, Object>> brokerLogin() {
        boolean success = angelOneClient.login();
        return ResponseEntity.ok(Map.of(
            "success", success,
            "authenticated", angelOneClient.isAuthenticated()
        ));
    }

    // ── GET /api/analyse/{symbol} ──────────────────────────────────────────────

    @GetMapping("/analyse/{symbol}")
    public ResponseEntity<StockAnalysisResult> analyseStock(@PathVariable String symbol) {
        StockAnalysisResult result = stockAnalysisService.analyse(symbol);
        return ResponseEntity.ok(result);
    }
}
