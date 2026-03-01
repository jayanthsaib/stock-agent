package com.jay.stagent.layer1_data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jay.stagent.config.AgentConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Layer 1 — Instrument Master Service.
 * Downloads the Angel One scrip master JSON, parses it, and provides
 * fast symbol→token lookups for the full NSE/BSE equity universe.
 *
 * Scrip master source:
 *   https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json
 *
 * JSON entry format:
 *   { "token": "2885", "symbol": "RELIANCE-EQ", "name": "RELIANCE INDUSTRIES",
 *     "exch_seg": "NSE", "instrumenttype": "", "lotsize": "1" }
 *
 * NSE equity symbols have a "-EQ" suffix which is stripped when building the key.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentMasterService {

    private static final String SCRIP_MASTER_URL =
        "https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json";

    // symbol (upper) -> token, keyed by exchange: "NSE" or "BSE"
    private final Map<String, Map<String, String>> tokenByExchange = new ConcurrentHashMap<>();
    // symbol (upper) -> Instrument detail, keyed by exchange
    private final Map<String, Map<String, Instrument>> instrumentByExchange = new ConcurrentHashMap<>();

    private final AgentConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private OkHttpClient http;

    record Instrument(String token, String symbol, String exchange, String name) {}

    // ── Fallback hardcoded map (used if download fails) ────────────────────────
    private static final Map<String, String> FALLBACK_NSE_TOKENS = Map.ofEntries(
        Map.entry("RELIANCE",    "2885"),
        Map.entry("TCS",         "11536"),
        Map.entry("INFY",        "1594"),
        Map.entry("HDFCBANK",    "1333"),
        Map.entry("ICICIBANK",   "4963"),
        Map.entry("KOTAKBANK",   "1922"),
        Map.entry("AXISBANK",    "5900"),
        Map.entry("SBIN",        "3045"),
        Map.entry("BAJFINANCE",  "317"),
        Map.entry("HINDUNILVR",  "1394"),
        Map.entry("ITC",         "1660"),
        Map.entry("LT",          "11483"),
        Map.entry("TITAN",       "3506"),
        Map.entry("ASIANPAINT",  "236"),
        Map.entry("NESTLEIND",   "17963"),
        Map.entry("WIPRO",       "3787"),
        Map.entry("HCLTECH",     "7229"),
        Map.entry("TECHM",       "13538"),
        Map.entry("SUNPHARMA",   "3351"),
        Map.entry("DRREDDY",     "881")
    );

    @PostConstruct
    public void init() {
        this.http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
        loadInstrumentMaster();
    }

    /** Reloads instrument master once daily at midnight. */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Kolkata")
    public void scheduledReload() {
        log.info("InstrumentMasterService: scheduled midnight reload");
        loadInstrumentMaster();
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Returns the Angel One numeric token for the given symbol on the given exchange,
     * or null if not found.
     */
    public String resolveToken(String symbol, String exchange) {
        Map<String, String> map = tokenByExchange.get(exchange.toUpperCase());
        if (map != null) {
            String token = map.get(symbol.toUpperCase());
            if (token != null) return token;
        }
        // Fallback to hardcoded map for NSE
        if ("NSE".equalsIgnoreCase(exchange)) {
            return FALLBACK_NSE_TOKENS.get(symbol.toUpperCase());
        }
        return null;
    }

    /**
     * Returns all equity symbols for the given exchange (NSE or BSE).
     * Returns an empty list if the master has not been loaded yet.
     */
    public List<String> getEquitySymbols(String exchange) {
        Map<String, String> map = tokenByExchange.get(exchange.toUpperCase());
        if (map == null || map.isEmpty()) {
            if ("NSE".equalsIgnoreCase(exchange)) {
                return new ArrayList<>(FALLBACK_NSE_TOKENS.keySet());
            }
            return List.of();
        }
        return new ArrayList<>(map.keySet());
    }

    /**
     * Returns the Instrument detail for a symbol, or null if not found.
     */
    public Instrument getInstrument(String symbol, String exchange) {
        Map<String, Instrument> map = instrumentByExchange.get(exchange.toUpperCase());
        return map != null ? map.get(symbol.toUpperCase()) : null;
    }

    /**
     * Total number of equity instruments loaded across all exchanges.
     */
    public int totalLoadedCount() {
        return tokenByExchange.values().stream().mapToInt(Map::size).sum();
    }

    // ── Internal loader ────────────────────────────────────────────────────────

    private void loadInstrumentMaster() {
        log.info("InstrumentMasterService: downloading scrip master from Angel One...");
        try {
            Request request = new Request.Builder()
                .url(SCRIP_MASTER_URL)
                .get()
                .addHeader("Accept", "application/json")
                .build();

            String json;
            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.error("InstrumentMasterService: HTTP {} — falling back to hardcoded map",
                        response.code());
                    applyFallback();
                    return;
                }
                json = response.body().string();
            }

            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                log.error("InstrumentMasterService: unexpected JSON format — falling back");
                applyFallback();
                return;
            }

            Map<String, Map<String, String>> newTokenMap = new ConcurrentHashMap<>();
            Map<String, Map<String, Instrument>> newInstrumentMap = new ConcurrentHashMap<>();

            boolean includeBse = config.filters().isIncludeBse();

            for (JsonNode entry : root) {
                String exchSeg = entry.path("exch_seg").asText("");
                String instrType = entry.path("instrumenttype").asText("").trim();

                // Only NSE (always) and BSE (if enabled)
                boolean isNse = "NSE".equalsIgnoreCase(exchSeg);
                boolean isBse = "BSE".equalsIgnoreCase(exchSeg) && includeBse;
                if (!isNse && !isBse) continue;

                // Only equity instruments: series "EQ" or symbol ending in "-EQ"
                // instrumenttype is blank for equities on Angel One scrip master
                String rawSymbol = entry.path("symbol").asText("").trim();
                if (!rawSymbol.endsWith("-EQ")) continue;
                if (!instrType.isEmpty() && !"EQ".equalsIgnoreCase(instrType)) continue;

                String symbol = rawSymbol.substring(0, rawSymbol.length() - 3).toUpperCase(); // strip "-EQ"
                String token  = entry.path("token").asText("").trim();
                String name   = entry.path("name").asText("").trim();
                String exch   = exchSeg.toUpperCase();

                if (symbol.isEmpty() || token.isEmpty()) continue;

                // Skip ETFs, liquid funds, gilt funds — only load tradable equities.
                // Filter by both instrument name and symbol to catch all variants.
                String nameUpper   = name.toUpperCase();
                String symbolUpper = symbol.toUpperCase();
                boolean isEtfByName = nameUpper.contains("ETF") || nameUpper.contains("BEES")
                        || nameUpper.contains("INDEX FUND") || nameUpper.contains("LIQUID FUND")
                        || nameUpper.contains("LIQUID BEES") || nameUpper.contains("GILT FUND");
                boolean isEtfBySymbol = symbolUpper.startsWith("LIQUID") || symbolUpper.startsWith("GILT")
                        || symbolUpper.endsWith("ETF") || symbolUpper.endsWith("IETF")
                        || symbolUpper.endsWith("BEES") || symbolUpper.contains("BETA")
                        || symbolUpper.contains("NIFTY") || symbolUpper.contains("SENSEX");
                if (isEtfByName || isEtfBySymbol) continue;

                newTokenMap.computeIfAbsent(exch, k -> new ConcurrentHashMap<>())
                    .put(symbol, token);
                newInstrumentMap.computeIfAbsent(exch, k -> new ConcurrentHashMap<>())
                    .put(symbol, new Instrument(token, symbol, exch, name));
            }

            // Atomic swap
            tokenByExchange.clear();
            tokenByExchange.putAll(newTokenMap);
            instrumentByExchange.clear();
            instrumentByExchange.putAll(newInstrumentMap);

            int nseCount = newTokenMap.getOrDefault("NSE", Map.of()).size();
            int bseCount = newTokenMap.getOrDefault("BSE", Map.of()).size();
            log.info("InstrumentMasterService: loaded {} NSE + {} BSE equity instruments",
                nseCount, bseCount);

        } catch (Exception e) {
            log.error("InstrumentMasterService: download failed ({}), using fallback", e.getMessage());
            applyFallback();
        }
    }

    private void applyFallback() {
        Map<String, String> nseMap = new ConcurrentHashMap<>(FALLBACK_NSE_TOKENS);
        tokenByExchange.put("NSE", nseMap);

        Map<String, Instrument> instrMap = new ConcurrentHashMap<>();
        FALLBACK_NSE_TOKENS.forEach((sym, tok) ->
            instrMap.put(sym, new Instrument(tok, sym, "NSE", sym)));
        instrumentByExchange.put("NSE", instrMap);

        log.warn("InstrumentMasterService: using fallback map with {} NSE symbols",
            FALLBACK_NSE_TOKENS.size());
    }
}
