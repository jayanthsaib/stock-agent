package com.jay.stagent.layer2_analysis;

import com.jay.stagent.config.AgentConfig;
import com.jay.stagent.model.OHLCVBar;
import com.jay.stagent.model.StockData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.*;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.num.DecimalNum;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Layer 2 — Technical Analysis Module.
 * Evaluates entry timing and price setup using ta4j 0.22.2 indicators.
 * Scores 0-100; a fundamentally strong stock is only traded when
 * technical setup confirms a favorable risk-reward entry point.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TechnicalAnalysisModule {

    private final AgentConfig config;

    public record TechnicalResult(
        double score,
        String summary,
        double sma200,
        double sma50,
        double sma20,
        double rsi,
        double supportLevel,
        double resistanceLevel,
        boolean goldenCross,
        boolean deathCross,
        boolean volumeConfirmed
    ) {}

    /**
     * Analyses the technical setup for a stock and returns a score 0-100.
     */
    public TechnicalResult analyse(StockData stock) {
        List<OHLCVBar> bars = stock.getHistoricalBars();
        if (bars == null || bars.size() < 210) {
            log.warn("Insufficient historical data for {} ({} bars) — returning 0",
                stock.getSymbol(), bars == null ? 0 : bars.size());
            return new TechnicalResult(0, "Insufficient data", 0, 0, 0, 50, 0, 0, false, false, false);
        }

        BarSeries series = buildSeries(stock.getSymbol(), bars);
        AgentConfig.Technical cfg = config.technical();

        ClosePriceIndicator close = new ClosePriceIndicator(series);

        // ── Moving Averages ────────────────────────────────────────────────────
        SMAIndicator sma200 = new SMAIndicator(close, cfg.getDmaLong());
        SMAIndicator sma50  = new SMAIndicator(close, cfg.getDmaMedium());
        SMAIndicator sma20  = new SMAIndicator(close, cfg.getDmaShort());

        int last = series.getEndIndex();
        double currentPrice = close.getValue(last).doubleValue();
        double v200 = sma200.getValue(last).doubleValue();
        double v50  = sma50.getValue(last).doubleValue();
        double v20  = sma20.getValue(last).doubleValue();

        // ── RSI ────────────────────────────────────────────────────────────────
        RSIIndicator rsi = new RSIIndicator(close, cfg.getRsiPeriod());
        double rsiVal = rsi.getValue(last).doubleValue();

        // ── MACD ───────────────────────────────────────────────────────────────
        MACDIndicator macd = new MACDIndicator(close, 12, 26);
        EMAIndicator signalLine = new EMAIndicator(macd, 9);
        double macdVal   = macd.getValue(last).doubleValue();
        double signalVal = signalLine.getValue(last).doubleValue();
        boolean macdBullish = macdVal > signalVal;

        double prevMacd   = last > 0 ? macd.getValue(last - 1).doubleValue() : macdVal;
        double prevSignal = last > 0 ? signalLine.getValue(last - 1).doubleValue() : signalVal;
        boolean macdJustCrossedUp = prevMacd < prevSignal && macdVal >= signalVal;

        // ── Volume ─────────────────────────────────────────────────────────────
        VolumeIndicator volume = new VolumeIndicator(series);
        SMAIndicator avgVol20 = new SMAIndicator(volume, 20);
        double currentVol = volume.getValue(last).doubleValue();
        double avgVol     = avgVol20.getValue(last).doubleValue();
        boolean volumeConfirmed = currentVol > avgVol;

        // ── Support/Resistance (20-day low/high) ───────────────────────────────
        double support    = computeSupport(bars, 20);
        double resistance = computeResistance(bars, 20);

        // ── Golden / Death Cross ───────────────────────────────────────────────
        double prev50  = last > 0 ? sma50.getValue(last - 1).doubleValue() : v50;
        double prev200 = last > 0 ? sma200.getValue(last - 1).doubleValue() : v200;
        boolean goldenCross = prev50 < prev200 && v50 >= v200;
        boolean deathCross  = prev50 > prev200 && v50 <= v200;

        // ── Scoring ────────────────────────────────────────────────────────────
        double score = 50.0;
        StringBuilder summary = new StringBuilder();

        // Price vs 200 DMA
        if (currentPrice > v200) {
            double pctAbove = ((currentPrice - v200) / v200) * 100;
            if (pctAbove <= cfg.getMaxPctAbove200dma()) {
                score += 15;
                summary.append("Above 200 DMA. ");
            } else {
                score -= 10;
                summary.append(String.format("%.1f%% above 200 DMA — extended. ", pctAbove));
            }
        } else {
            score -= 25;
            summary.append("Below 200 DMA — avoid. ");
        }

        // Price vs 50 DMA
        if (currentPrice > v50) { score += 8; summary.append("Above 50 DMA. "); }
        else score -= 8;

        // Price vs 20 DMA
        if (currentPrice > v20) score += 5;

        // Golden / Death cross
        if (goldenCross) { score += 12; summary.append("Golden cross. "); }
        if (deathCross)  { score -= 20; summary.append("Death cross — bearish. "); }

        // RSI
        if (rsiVal < cfg.getRsiOversold() && rsiVal > 30) {
            score += 8; summary.append(String.format("RSI %.0f — oversold potential. ", rsiVal));
        } else if (rsiVal >= 40 && rsiVal <= 60) {
            score += 5; summary.append(String.format("RSI %.0f — neutral. ", rsiVal));
        } else if (rsiVal > cfg.getRsiOverbought()) {
            score -= 15; summary.append(String.format("RSI %.0f — overbought. ", rsiVal));
        } else if (rsiVal <= 30) {
            score -= 5; summary.append(String.format("RSI %.0f — deeply oversold. ", rsiVal));
        }

        // MACD
        if (macdJustCrossedUp) { score += 10; summary.append("MACD bullish crossover. "); }
        else if (macdBullish)  { score += 5; }
        else                   { score -= 5; }

        // Volume
        if (volumeConfirmed) { score += 7; summary.append("Volume confirmed. "); }
        else                 { score -= 5; summary.append("Low volume. "); }

        score = Math.max(0, Math.min(100, score));
        log.debug("Technical score for {}: {} | RSI={} MACD={} Vol={}",
            stock.getSymbol(), score, rsiVal, macdBullish, volumeConfirmed);

        return new TechnicalResult(
            score, summary.toString().trim(),
            v200, v50, v20, rsiVal,
            support, resistance,
            goldenCross, deathCross, volumeConfirmed
        );
    }

    private double computeSupport(List<OHLCVBar> bars, int lookback) {
        int start = Math.max(0, bars.size() - lookback);
        return bars.subList(start, bars.size()).stream()
            .mapToDouble(OHLCVBar::getLow).min().orElse(0);
    }

    private double computeResistance(List<OHLCVBar> bars, int lookback) {
        int start = Math.max(0, bars.size() - lookback);
        return bars.subList(start, bars.size()).stream()
            .mapToDouble(OHLCVBar::getHigh).max().orElse(0);
    }

    /**
     * Weekly trend confirmation — derived from daily bars, no extra API call.
     * Requires the weekly price to be above the 20-week SMA and weekly RSI not collapsing.
     * Returns true if the weekly timeframe confirms a bullish setup.
     */
    public record WeeklyConfirmation(boolean confirmed, String reason) {}

    public WeeklyConfirmation analyseWeeklyTrend(String symbol, List<OHLCVBar> dailyBars) {
        List<OHLCVBar> weekly = aggregateToWeekly(dailyBars);
        if (weekly.size() < 20) {
            return new WeeklyConfirmation(true, "Insufficient weekly data — skipping filter");
        }

        BarSeries series = buildSeries(symbol + "_weekly", weekly);
        int last = series.getEndIndex();
        ClosePriceIndicator close = new ClosePriceIndicator(series);

        SMAIndicator sma20w = new SMAIndicator(close, Math.min(20, weekly.size()));
        RSIIndicator  rsi14w = new RSIIndicator(close, Math.min(14, weekly.size() - 1));

        double price   = close.getValue(last).doubleValue();
        double sma20wV = sma20w.getValue(last).doubleValue();
        double rsiW    = rsi14w.getValue(last).doubleValue();

        // Weekly RSI collapsing: compare to 2 weeks ago
        double rsiWPrev = last >= 2 ? rsi14w.getValue(last - 2).doubleValue() : rsiW;
        boolean rsiCollapsing = rsiW < 40 && rsiW < rsiWPrev - 5;

        boolean aboveWeeklySma = price >= sma20wV;

        if (!aboveWeeklySma && rsiCollapsing) {
            return new WeeklyConfirmation(false,
                String.format("Weekly: price below 20w SMA (%.0f vs %.0f) + RSI collapsing (%.0f) — bearish",
                    price, sma20wV, rsiW));
        }
        if (!aboveWeeklySma) {
            return new WeeklyConfirmation(false,
                String.format("Weekly: price below 20w SMA (%.0f vs %.0f) — downtrend",
                    price, sma20wV));
        }
        if (rsiCollapsing) {
            return new WeeklyConfirmation(false,
                String.format("Weekly RSI collapsing (%.0f) — momentum failing", rsiW));
        }

        return new WeeklyConfirmation(true,
            String.format("Weekly confirmed: above 20w SMA, RSI=%.0f", rsiW));
    }

    /**
     * Aggregates daily OHLCV bars into weekly bars (Mon open → Fri close).
     */
    private List<OHLCVBar> aggregateToWeekly(List<OHLCVBar> daily) {
        // Key: "YYYY-WW" (ISO year + week number)
        TreeMap<String, List<OHLCVBar>> byWeek = new TreeMap<>();
        for (OHLCVBar bar : daily) {
            int year = bar.getTimestamp().get(IsoFields.WEEK_BASED_YEAR);
            int week = bar.getTimestamp().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            String key = String.format("%04d-%02d", year, week);
            byWeek.computeIfAbsent(key, k -> new ArrayList<>()).add(bar);
        }

        List<OHLCVBar> weekly = new ArrayList<>();
        for (Map.Entry<String, List<OHLCVBar>> entry : byWeek.entrySet()) {
            List<OHLCVBar> week = entry.getValue();
            double open   = week.get(0).getOpen();
            double close  = week.get(week.size() - 1).getClose();
            double high   = week.stream().mapToDouble(OHLCVBar::getHigh).max().orElse(close);
            double low    = week.stream().mapToDouble(OHLCVBar::getLow).min().orElse(close);
            long   volume = week.stream().mapToLong(OHLCVBar::getVolume).sum();
            weekly.add(OHLCVBar.builder()
                .timestamp(week.get(week.size() - 1).getTimestamp())
                .open(open).high(high).low(low).close(close).volume(volume)
                .build());
        }
        return weekly;
    }

    /**
     * Builds a ta4j BarSeries from OHLCVBar list.
     * Uses the correct ta4j 0.22.2 BaseBar constructor:
     *   BaseBar(Duration, Instant beginTime, Instant endTime, Num open, Num high, Num low, Num close, Num amount, Num volume, long trades)
     */
    private BarSeries buildSeries(String symbol, List<OHLCVBar> bars) {
        BarSeries series = new BaseBarSeriesBuilder().withName(symbol).build();
        for (OHLCVBar bar : bars) {
            Instant endTime   = bar.getTimestamp().atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            Instant beginTime = endTime.minus(Duration.ofDays(1));

            series.addBar(new BaseBar(
                Duration.ofDays(1),
                beginTime,
                endTime,
                DecimalNum.valueOf(bar.getOpen()),
                DecimalNum.valueOf(bar.getHigh()),
                DecimalNum.valueOf(bar.getLow()),
                DecimalNum.valueOf(bar.getClose()),
                DecimalNum.valueOf(0),           // amount (not available, set to 0)
                DecimalNum.valueOf(bar.getVolume()),
                0L                              // trade count (not available)
            ));
        }
        return series;
    }
}
