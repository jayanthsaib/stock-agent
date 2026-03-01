package com.jay.stagent.layer1_data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jay.stagent.config.AgentConfig;
import com.jay.stagent.model.MacroData;
import com.jay.stagent.model.OHLCVBar;
import com.jay.stagent.model.StockData;
import com.jay.stagent.model.enums.MarketRegime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Layer 1 — Data Ingestion Engine.
 * Pulls live and historical market data, caches it, and provides it to the analysis layers.
 *
 * Two-phase pre-filter strategy (fits in the 08:45–09:15 window):
 *   Phase 1: Batch live-quote calls to Angel One to filter the full NSE universe
 *            down to stocks meeting the price + volume floor (~8 API calls, ~5 s).
 *   Phase 2: Fetch 1-year daily OHLCV for each candidate in parallel, capped at
 *            10 concurrent requests via a Semaphore (~3–5 min for 300–500 stocks).
 *
 * Watchlist stocks are always included, regardless of Phase-1 filter.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataIngestionEngine {

    private final AngelOneClient angelOneClient;
    private final AgentConfig config;
    private final InstrumentMasterService instrumentMaster;
    private final PortfolioValueService portfolioValueService;

    // In-memory cache (refreshed every market day)
    private final Map<String, StockData> stockDataCache = new ConcurrentHashMap<>();
    private MacroData latestMacroData;
    private LocalDateTime lastCacheRefresh;

    // Rate-limiter: cap concurrent historical fetches to 10 at a time
    private final Semaphore fetchSemaphore = new Semaphore(10);
    private final ExecutorService fetchExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // Ordered list of symbols that were analysed in the last refresh cycle
    private final List<String> analysisUniverse = new CopyOnWriteArrayList<>();

    private static final int QUOTE_BATCH_SIZE = 250; // Angel One getQuote() limit

    private final OkHttpClient yahooClient = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Refreshes all market data using the two-phase approach.
     * Called by TradingScheduler at pre-market and market open.
     */
    public void refreshAll() {
        log.info("DataIngestionEngine: starting full data refresh");
        portfolioValueService.refresh();
        refreshHistoricalData();
        refreshMacroData();
        lastCacheRefresh = LocalDateTime.now();
        log.info("DataIngestionEngine: refresh complete. {} symbols cached", stockDataCache.size());
    }

    /**
     * Returns cached StockData for a symbol. Fetches live if not cached.
     */
    public StockData getStockData(String symbol) {
        return stockDataCache.computeIfAbsent(symbol, this::fetchStockData);
    }

    /**
     * Always fetches fresh data for a symbol, bypassing the cache.
     * Used for on-demand single-stock analysis.
     */
    public StockData getStockDataFresh(String symbol) {
        stockDataCache.remove(symbol.toUpperCase());
        return getStockData(symbol.toUpperCase());
    }

    /**
     * Returns the latest macro snapshot. Falls back to a neutral default if unavailable.
     */
    public MacroData getMacroData() {
        if (latestMacroData == null) {
            refreshMacroData();
        }
        return latestMacroData != null ? latestMacroData : buildNeutralMacroData();
    }

    /**
     * Returns cached StockData for the configured watchlist (backward compatibility).
     */
    public List<StockData> getAllWatchlistData() {
        return config.watchlist().stream()
            .map(this::getStockData)
            .filter(sd -> sd != null && sd.getLtp() > 0)
            .toList();
    }

    /**
     * Returns cached StockData for the full analysis universe built by the last refresh.
     * Watchlist stocks come first in the list.
     */
    public List<StockData> getAllEquityData() {
        Set<String> watchlistSet = new HashSet<>(config.watchlist());
        List<StockData> result = new ArrayList<>();

        // Watchlist first
        for (String sym : config.watchlist()) {
            StockData sd = stockDataCache.get(sym);
            if (sd != null && sd.getLtp() > 0) result.add(sd);
        }
        // Remaining universe
        for (String sym : analysisUniverse) {
            if (!watchlistSet.contains(sym)) {
                StockData sd = stockDataCache.get(sym);
                if (sd != null && sd.getLtp() > 0) result.add(sd);
            }
        }
        return result;
    }

    /**
     * Resolves the Angel One token for a symbol, delegating to InstrumentMasterService.
     */
    public String resolveToken(String symbol) {
        String token = instrumentMaster.resolveToken(symbol, "NSE");
        if (token == null) token = instrumentMaster.resolveToken(symbol, "BSE");
        return token;
    }

    // ── Phase 1: Batch Quote Filter ───────────────────────────────────────────

    /**
     * Phase 1: calls getQuote() in batches to quickly filter the universe.
     * Returns symbols that pass the price and traded-value filters,
     * plus all watchlist symbols unconditionally.
     */
    private List<String> phase1QuoteFilter() {
        List<String> nseSymbols = instrumentMaster.getEquitySymbols("NSE");
        List<String> bseSymbols = config.filters().isIncludeBse()
            ? instrumentMaster.getEquitySymbols("BSE") : List.of();

        log.info("Phase 1: scanning {} NSE + {} BSE symbols via live quotes",
            nseSymbols.size(), bseSymbols.size());

        Set<String> watchlistSet = new HashSet<>(config.watchlist());
        Set<String> candidates = new LinkedHashSet<>(watchlistSet); // watchlist always included

        double minPrice = config.filters().getMinStockPriceInr();
        double minVolumeCr = config.filters().getMinAvgDailyVolumeCr();

        filterExchange(nseSymbols, "NSE", minPrice, minVolumeCr, candidates);
        if (config.filters().isIncludeBse()) {
            filterExchange(bseSymbols, "BSE", minPrice, minVolumeCr, candidates);
        }

        log.info("Phase 1 complete: {} candidates pass price/volume filter", candidates.size());
        return new ArrayList<>(candidates);
    }

    private void filterExchange(List<String> symbols, String exchange,
                                 double minPrice, double minVolumeCr,
                                 Set<String> candidates) {
        Set<String> watchlistSet = new HashSet<>(config.watchlist());

        // Build token→symbol reverse map for this batch
        List<String> tokenList = new ArrayList<>();
        Map<String, String> tokenToSymbol = new LinkedHashMap<>();
        for (String sym : symbols) {
            String token = instrumentMaster.resolveToken(sym, exchange);
            if (token != null) {
                tokenList.add(token);
                tokenToSymbol.put(token, sym);
            }
        }

        // Batch into groups of QUOTE_BATCH_SIZE
        for (int i = 0; i < tokenList.size(); i += QUOTE_BATCH_SIZE) {
            List<String> batch = tokenList.subList(i, Math.min(i + QUOTE_BATCH_SIZE, tokenList.size()));
            try {
                JsonNode data = angelOneClient.getQuote(exchange, batch);
                if (data == null || data.isMissingNode()) continue;

                JsonNode fetched = data.path("fetched");
                if (fetched.isArray()) {
                    for (JsonNode item : fetched) {
                        String token   = item.path("symbolToken").asText("");
                        double ltp     = item.path("ltp").asDouble(0);
                        double tradedVal = item.path("totaltradedvalue").asDouble(0);
                        double tradedValCr = tradedVal / 10_000_000.0;

                        String sym = tokenToSymbol.get(token);
                        if (sym == null) continue;

                        // Watchlist always added (already in candidates)
                        if (watchlistSet.contains(sym)) continue;

                        if (ltp >= minPrice && tradedValCr >= minVolumeCr) {
                            candidates.add(sym);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Phase 1 quote batch failed (exchange={}, batch starting {}): {}",
                    exchange, i, e.getMessage());
            }
        }
    }

    // ── Phase 2: Historical Data Fetch ────────────────────────────────────────

    private void refreshHistoricalData() {
        // Phase 1: get filtered candidate list
        List<String> candidates = phase1QuoteFilter();

        // Apply universe cap — watchlist stocks get priority slots
        int cap = config.filters().getMaxAnalysisUniverse();
        Set<String> watchlistSet = new HashSet<>(config.watchlist());
        List<String> prioritised = new ArrayList<>();
        // Add watchlist first
        for (String sym : candidates) {
            if (watchlistSet.contains(sym)) prioritised.add(sym);
        }
        // Add remaining up to cap
        for (String sym : candidates) {
            if (!watchlistSet.contains(sym) && prioritised.size() < cap) {
                prioritised.add(sym);
            }
        }

        log.info("Phase 2: fetching 1-year OHLCV for {} symbols (cap={})", prioritised.size(), cap);

        analysisUniverse.clear();
        analysisUniverse.addAll(prioritised);

        String today   = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String yearAgo = LocalDateTime.now().minusYears(1)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        List<CompletableFuture<Void>> futures = prioritised.stream()
            .map(symbol -> CompletableFuture.runAsync(() -> {
                try {
                    fetchSemaphore.acquire();
                    try {
                        StockData data = fetchStockData(symbol, yearAgo, today);
                        if (data != null) {
                            // Apply 20-day avg volume filter (more precise than Phase 1)
                            double minVolumeCr = config.filters().getMinAvgDailyVolumeCr();
                            double avgVolCr = data.getAvgVolume20d() * data.getLtp() / 10_000_000.0;
                            boolean watchlisted = watchlistSet.contains(symbol);
                            if (watchlisted || avgVolCr >= minVolumeCr) {
                                stockDataCache.put(symbol, data);
                            }
                        }
                    } finally {
                        fetchSemaphore.release();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("Phase 2 fetch interrupted for {}", symbol);
                }
            }, fetchExecutor))
            .toList();

        // Wait for all fetches to complete
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(10, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            log.warn("Phase 2: timed out after 10 min — partial data available");
        } catch (Exception e) {
            log.error("Phase 2: fetch batch error: {}", e.getMessage());
        }

        log.info("Phase 2 complete: {} symbols in cache", stockDataCache.size());
    }

    private StockData fetchStockData(String symbol) {
        String today   = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String yearAgo = LocalDateTime.now().minusYears(1)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return fetchStockData(symbol, yearAgo, today);
    }

    private StockData fetchStockData(String symbol, String fromDate, String toDate) {
        String token = resolveToken(symbol);
        if (token == null) {
            log.warn("No token found for symbol: {}", symbol);
            return null;
        }

        List<OHLCVBar> bars = angelOneClient.getHistoricalData(token, "NSE", "ONE_DAY", fromDate, toDate);
        if (bars.isEmpty()) {
            log.debug("No historical data returned for {}", symbol);
            return null;
        }

        OHLCVBar latest = bars.get(bars.size() - 1);
        double avgVol20d = computeAvgVolume(bars, 20);

        return StockData.builder()
            .symbol(symbol)
            .exchange("NSE")
            .ltp(latest.getClose())
            .open(latest.getOpen())
            .high(latest.getHigh())
            .low(latest.getLow())
            .close(latest.getClose())
            .volume(latest.getVolume())
            .avgVolume20d(avgVol20d)
            .marketCapCr(0)
            .fetchedAt(LocalDateTime.now())
            .historicalBars(bars)
            .build();
    }

    // ── Macro Data ─────────────────────────────────────────────────────────────

    private List<OHLCVBar> fetchNiftyFromYahoo() {
        String url = "https://query1.finance.yahoo.com/v8/finance/chart/%5ENSEI?interval=1d&range=1y";
        try {
            Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Accept", "application/json")
                .get()
                .build();
            try (Response response = yahooClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("Yahoo Finance Nifty fetch failed: HTTP {}", response.code());
                    return List.of();
                }
                JsonNode root = objectMapper.readTree(response.body().string());
                JsonNode result = root.path("chart").path("result");
                if (!result.isArray() || result.isEmpty()) {
                    log.warn("Yahoo Finance Nifty: empty result");
                    return List.of();
                }
                JsonNode timestamps = result.get(0).path("timestamp");
                JsonNode closes = result.get(0).path("indicators").path("quote").get(0).path("close");
                List<OHLCVBar> bars = new ArrayList<>();
                for (int i = 0; i < timestamps.size(); i++) {
                    if (i >= closes.size() || closes.get(i).isNull()) continue;
                    double close = closes.get(i).asDouble(0);
                    if (close <= 0) continue;
                    LocalDateTime ts = LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(timestamps.get(i).asLong()), ZoneOffset.UTC);
                    bars.add(OHLCVBar.builder()
                        .timestamp(ts).open(close).high(close).low(close).close(close).volume(0)
                        .build());
                }
                log.info("Yahoo Finance Nifty: fetched {} bars", bars.size());
                return bars;
            }
        } catch (Exception e) {
            log.error("Yahoo Finance Nifty fetch failed: {}", e.getMessage());
            return List.of();
        }
    }

    private void refreshMacroData() {
        try {
            List<OHLCVBar> niftyBars = fetchNiftyFromYahoo();

            double niftyPrice  = 0;
            double nifty200dma = 0;

            if (!niftyBars.isEmpty()) {
                niftyPrice  = niftyBars.get(niftyBars.size() - 1).getClose();
                nifty200dma = computeSMA(niftyBars, 200);
            }

            double pctAboveDma = nifty200dma > 0
                ? ((niftyPrice - nifty200dma) / nifty200dma) * 100 : 0;

            double indiaVix = fetchIndiaVix();
            MarketRegime regime = determineRegime(indiaVix, niftyPrice, nifty200dma);
            boolean newBuysSuppressed = indiaVix > config.macro().getVixNoBuysThreshold()
                || niftyPrice < nifty200dma * 0.95;

            this.latestMacroData = MacroData.builder()
                .date(LocalDate.now())
                .indiaVix(indiaVix)
                .nifty50Price(niftyPrice)
                .nifty50Dma200(nifty200dma)
                .nifty50PctAboveDma200(pctAboveDma)
                .fiiNetFlowCr(0)
                .consecutiveFiiSellingDays(0)
                .repoRatePct(6.5)
                .cpiInflationPct(5.0)
                .usdInrRate(84.0)
                .advanceDeclineRatio(1.0)
                .regime(regime)
                .newBuysSuppressed(newBuysSuppressed)
                .build();

            log.info("Macro refresh: Nifty={} VIX={} Regime={} BuysSuppressed={}",
                niftyPrice, indiaVix, regime, newBuysSuppressed);

        } catch (Exception e) {
            log.error("Macro data refresh failed: {}", e.getMessage());
        }
    }

    private double fetchIndiaVix() {
        try {
            var vixData = angelOneClient.getHistoricalData("26000", "NSE", "ONE_DAY",
                LocalDateTime.now().minusDays(5).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        } catch (Exception ignored) {}
        return 15.0;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private double computeAvgVolume(List<OHLCVBar> bars, int days) {
        if (bars.size() < days) return bars.stream().mapToLong(OHLCVBar::getVolume).average().orElse(0);
        return bars.subList(bars.size() - days, bars.size())
            .stream().mapToLong(OHLCVBar::getVolume).average().orElse(0);
    }

    private double computeSMA(List<OHLCVBar> bars, int period) {
        if (bars.size() < period) return bars.stream().mapToDouble(OHLCVBar::getClose).average().orElse(0);
        return bars.subList(bars.size() - period, bars.size())
            .stream().mapToDouble(OHLCVBar::getClose).average().orElse(0);
    }

    private MarketRegime determineRegime(double vix, double niftyPrice, double nifty200dma) {
        AgentConfig.Macro m = config.macro();
        if (vix > m.getVixNoBuysThreshold() && niftyPrice < nifty200dma) return MarketRegime.BEAR;
        if (vix > m.getVixCautionThreshold()) return MarketRegime.HIGH_VOLATILITY;
        if (niftyPrice > nifty200dma * 1.05 && vix < m.getVixFavorableThreshold()) return MarketRegime.BULL;
        return MarketRegime.SIDEWAYS;
    }

    private MacroData buildNeutralMacroData() {
        return MacroData.builder()
            .date(LocalDate.now())
            .indiaVix(15.0)
            .nifty50Price(22000)
            .nifty50Dma200(21000)
            .nifty50PctAboveDma200(4.76)
            .regime(MarketRegime.SIDEWAYS)
            .newBuysSuppressed(false)
            .build();
    }
}
