package com.jay.stagent.layer3_signal;

import com.jay.stagent.layer1_data.DataIngestionEngine;
import com.jay.stagent.model.OHLCVBar;
import com.jay.stagent.model.StockData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Layer 3 — Backtesting Engine.
 *
 * Replays the agent's buy strategy on 1-year OHLCV data cached from Phase 2.
 * Strategy (mirrors live signal rules):
 *   BUY  : Close > SMA200, SMA20 > SMA50 (golden cross), RSI between 35–70
 *   EXIT : Stop-loss at -8% from entry | Target at +16% | End of data
 *
 * One position per symbol at a time. Uses simple fixed-capital sizing.
 * Results show win rate, average return, max drawdown, and equity curve.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestEngine {

    private final DataIngestionEngine dataEngine;

    private static final double SL_PCT     = 0.08;  // 8%  stop-loss
    private static final double TARGET_PCT = 0.16;  // 16% target  (2× SL → R:R = 2)
    private static final int    WARMUP     = 200;   // bars needed for SMA200

    // ── Public API ─────────────────────────────────────────────────────────────

    public record Trade(
        String symbol,
        LocalDate entryDate,
        double entryPrice,
        LocalDate exitDate,
        double exitPrice,
        String exitReason,   // "TARGET", "STOP_LOSS", "END_OF_DATA"
        double returnPct
    ) {}

    public record SymbolResult(
        String symbol,
        int totalTrades,
        int wins,
        double winRate,
        double avgReturnPct,
        double totalReturnPct,
        double maxDrawdownPct,
        List<Trade> trades
    ) {}

    public record BacktestReport(
        int symbolsAnalysed,
        int symbolsWithTrades,
        int totalTrades,
        int totalWins,
        double overallWinRate,
        double avgReturnPerTrade,
        double avgMaxDrawdown,
        List<SymbolResult> results
    ) {}

    /**
     * Runs the backtest on the given symbols. If symbols is empty, uses the full cached universe.
     */
    public BacktestReport run(List<String> symbols) {
        List<StockData> universe = symbols.isEmpty()
            ? dataEngine.getAllEquityData()
            : symbols.stream()
                .map(s -> dataEngine.getStockData(s.toUpperCase()))
                .filter(Objects::nonNull)
                .toList();

        log.info("BacktestEngine: running on {} symbols", universe.size());

        List<SymbolResult> results = new ArrayList<>();
        for (StockData sd : universe) {
            try {
                SymbolResult r = backtestSymbol(sd);
                if (r != null) results.add(r);
            } catch (Exception e) {
                log.warn("Backtest failed for {}: {}", sd.getSymbol(), e.getMessage());
            }
        }

        // Aggregate stats
        int totalTrades = results.stream().mapToInt(SymbolResult::totalTrades).sum();
        int totalWins   = results.stream().mapToInt(SymbolResult::wins).sum();
        double overallWinRate = totalTrades > 0 ? (double) totalWins / totalTrades * 100 : 0;
        double avgReturn = results.stream()
            .filter(r -> r.totalTrades() > 0)
            .mapToDouble(SymbolResult::avgReturnPct).average().orElse(0);
        double avgDrawdown = results.stream()
            .filter(r -> r.totalTrades() > 0)
            .mapToDouble(SymbolResult::maxDrawdownPct).average().orElse(0);

        // Sort by total return descending
        results.sort(Comparator.comparingDouble(SymbolResult::totalReturnPct).reversed());

        log.info("Backtest complete: {} symbols, {} trades, win rate {:.1f}%",
            universe.size(), totalTrades, overallWinRate);

        return new BacktestReport(
            universe.size(),
            (int) results.stream().filter(r -> r.totalTrades() > 0).count(),
            totalTrades,
            totalWins,
            overallWinRate,
            avgReturn,
            avgDrawdown,
            results
        );
    }

    // ── Per-Symbol Simulation ─────────────────────────────────────────────────

    private SymbolResult backtestSymbol(StockData sd) {
        List<OHLCVBar> bars = sd.getHistoricalBars();
        if (bars == null || bars.size() < WARMUP + 10) return null;

        List<Trade> trades = new ArrayList<>();
        boolean inPosition = false;
        double entryPrice = 0;
        LocalDate entryDate = null;
        double peak = 1.0, equityMin = 1.0, equity = 1.0;
        double maxDrawdown = 0;

        for (int i = WARMUP; i < bars.size(); i++) {
            OHLCVBar bar = bars.get(i);
            double close = bar.getClose();
            if (close <= 0) continue;

            // Compute indicators
            double sma200 = sma(bars, i, 200);
            double sma50  = sma(bars, i, 50);
            double sma20  = sma(bars, i, 20);
            double rsi    = rsi(bars, i, 14);

            if (inPosition) {
                // Check exit conditions
                double returnPct = (close - entryPrice) / entryPrice * 100;
                String exitReason = null;

                if (close <= entryPrice * (1 - SL_PCT))     exitReason = "STOP_LOSS";
                else if (close >= entryPrice * (1 + TARGET_PCT)) exitReason = "TARGET";
                else if (i == bars.size() - 1)               exitReason = "END_OF_DATA";

                if (exitReason != null) {
                    double actualReturn = (close - entryPrice) / entryPrice * 100;
                    if ("STOP_LOSS".equals(exitReason))  actualReturn = -SL_PCT * 100;
                    if ("TARGET".equals(exitReason))     actualReturn =  TARGET_PCT * 100;

                    trades.add(new Trade(
                        sd.getSymbol(), entryDate, entryPrice,
                        bar.getTimestamp().toLocalDate(), close, exitReason, actualReturn));

                    equity *= (1 + actualReturn / 100);
                    peak = Math.max(peak, equity);
                    double dd = (peak - equity) / peak * 100;
                    maxDrawdown = Math.max(maxDrawdown, dd);
                    inPosition = false;
                }
            } else {
                // Buy signal: above 200 DMA, golden cross (20>50), RSI in range
                boolean signal = close > sma200
                    && sma20 > sma50
                    && rsi >= 35 && rsi <= 70;

                if (signal) {
                    inPosition = true;
                    entryPrice = close;
                    entryDate  = bar.getTimestamp().toLocalDate();
                }
            }
        }

        if (trades.isEmpty()) return null;

        int wins = (int) trades.stream().filter(t -> t.returnPct() > 0).count();
        double avgReturn    = trades.stream().mapToDouble(Trade::returnPct).average().orElse(0);
        double totalReturn  = trades.stream().mapToDouble(Trade::returnPct).sum();
        double winRate      = (double) wins / trades.size() * 100;

        return new SymbolResult(
            sd.getSymbol(), trades.size(), wins, winRate,
            avgReturn, totalReturn, maxDrawdown, trades);
    }

    // ── Indicator Helpers ─────────────────────────────────────────────────────

    private double sma(List<OHLCVBar> bars, int endIdx, int period) {
        int start = endIdx - period + 1;
        if (start < 0) return 0;
        double sum = 0;
        for (int i = start; i <= endIdx; i++) sum += bars.get(i).getClose();
        return sum / period;
    }

    private double rsi(List<OHLCVBar> bars, int endIdx, int period) {
        if (endIdx < period) return 50;
        double gains = 0, losses = 0;
        for (int i = endIdx - period + 1; i <= endIdx; i++) {
            double change = bars.get(i).getClose() - bars.get(i - 1).getClose();
            if (change > 0) gains  += change;
            else            losses -= change;
        }
        if (losses == 0) return 100;
        double rs = (gains / period) / (losses / period);
        return 100 - (100 / (1 + rs));
    }
}
