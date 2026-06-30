package com.jay.stagent.layer1_data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jay.stagent.config.AgentConfig;
import com.jay.stagent.model.MFSchemeData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Layer 1 — Mutual Fund Data Ingestion Engine.
 *
 * Phase 1: Fetches the full AMFI scheme list from mfapi.in and filters to
 *          direct equity plans matching configured categories (~300 schemes).
 * Phase 2: Fetches NAV history per scheme and computes CAGR, Sharpe,
 *          momentum, consistency metrics. Rate-limited to 200ms between calls.
 *
 * Data source: https://api.mfapi.in (free, public AMFI NAV API)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MFDataIngestionEngine {

    private final AgentConfig config;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, MFSchemeData> schemeCache = new ConcurrentHashMap<>();
    private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);

    public Map<String, MFSchemeData> getCachedSchemes() {
        return Collections.unmodifiableMap(schemeCache);
    }

    public boolean isRefreshInProgress() {
        return refreshInProgress.get();
    }

    /**
     * Full two-phase refresh. Blocks until complete.
     * Expected runtime: ~60–120 seconds for 300 schemes.
     */
    public void refresh() {
        if (!refreshInProgress.compareAndSet(false, true)) {
            log.warn("MF refresh already in progress — skipping duplicate call");
            return;
        }
        try {
            log.info("MFDataIngestionEngine: starting refresh");
            schemeCache.clear();

            List<SchemeRef> phase1 = phase1FetchAndFilter();
            log.info("MFDataIngestionEngine: {} direct equity schemes to fetch in Phase 2", phase1.size());

            phase2FetchNavHistory(phase1);
            log.info("MFDataIngestionEngine: refresh complete — {} schemes cached", schemeCache.size());
        } catch (Exception e) {
            log.error("MF refresh failed: {}", e.getMessage(), e);
        } finally {
            refreshInProgress.set(false);
        }
    }

    // ── Phase 1 ───────────────────────────────────────────────────────────────

    private List<SchemeRef> phase1FetchAndFilter() {
        String url = "https://api.mfapi.in/mf";
        try {
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.error("AMFI scheme list fetch failed: HTTP {}", resp.code());
                    return List.of();
                }
                JsonNode arr = mapper.readTree(resp.body().string());
                if (!arr.isArray()) return List.of();

                List<String> categories = config.mutualFunds().getCategoriesToInclude();
                int cap = config.mutualFunds().getMaxSchemesToAnalyse();

                List<SchemeRef> result = new ArrayList<>();
                for (JsonNode node : arr) {
                    if (result.size() >= cap) break;
                    String code = node.path("schemeCode").asText("");
                    String name = node.path("schemeName").asText("");
                    if (code.isBlank() || name.isBlank()) continue;

                    String nameLower = name.toLowerCase();
                    if (!nameLower.contains("direct")) continue;
                    boolean categoryMatch = categories.stream()
                        .anyMatch(cat -> nameLower.contains(cat.toLowerCase()));
                    if (!categoryMatch) continue;

                    result.add(new SchemeRef(code, name));
                }
                return result;
            }
        } catch (Exception e) {
            log.error("Phase 1 AMFI list fetch failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Phase 2 ───────────────────────────────────────────────────────────────

    private void phase2FetchNavHistory(List<SchemeRef> schemes) {
        List<String> categories = config.mutualFunds().getCategoriesToInclude();

        for (SchemeRef ref : schemes) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                JsonNode detail = fetchSchemeDetail(ref.code());
                if (detail == null) continue;

                String category = detail.path("meta").path("scheme_category").asText("");
                boolean catMatch = categories.stream()
                    .anyMatch(cat -> category.toLowerCase().contains(cat.toLowerCase()));
                if (!catMatch) continue;

                String fundHouse = detail.path("meta").path("fund_house").asText("");
                JsonNode navData = detail.path("data");
                if (!navData.isArray() || navData.size() < 63) continue;

                double currentNav = navData.get(0).path("nav").asDouble(0);
                if (currentNav <= 0) continue;

                MFSchemeData scheme = MFSchemeData.builder()
                    .schemeCode(ref.code())
                    .schemeName(ref.name())
                    .category(category)
                    .fundHouse(fundHouse)
                    .isDirect(ref.name().toLowerCase().contains("direct"))
                    .currentNav(currentNav)
                    .nav52wHigh(compute52wHigh(navData))
                    .nav52wLow(compute52wLow(navData))
                    .cagr3y(computeCAGR(navData, 3))
                    .sharpeRatio(computeSharpe(navData))
                    .return1y(computeReturn(navData, 252))
                    .return6m(computeReturn(navData, 126))
                    .return3m(computeReturn(navData, 63))
                    .consistencyCount(computeConsistency(navData))
                    .navHistory(List.of())  // not stored to save memory
                    .build();

                schemeCache.put(ref.code(), scheme);
            } catch (Exception e) {
                log.debug("Phase 2 failed for scheme {}: {}", ref.code(), e.getMessage());
            }
        }
    }

    private JsonNode fetchSchemeDetail(String schemeCode) {
        String url = "https://api.mfapi.in/mf/" + schemeCode;
        try {
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                return mapper.readTree(resp.body().string());
            }
        } catch (Exception e) {
            log.debug("Scheme detail fetch failed for {}: {}", schemeCode, e.getMessage());
            return null;
        }
    }

    // ── Computations ──────────────────────────────────────────────────────────

    private double computeCAGR(JsonNode navData, int years) {
        try {
            int idx = Math.min(years * 252, navData.size() - 1);
            double current = navData.get(0).path("nav").asDouble(0);
            double old = navData.get(idx).path("nav").asDouble(0);
            if (old <= 0 || current <= 0) return 0;
            return (Math.pow(current / old, 1.0 / years) - 1) * 100;
        } catch (Exception e) { return 0; }
    }

    private double computeSharpe(JsonNode navData) {
        try {
            int days = Math.min(252, navData.size() - 1);
            if (days < 30) return 0;
            double[] returns = new double[days];
            for (int i = 0; i < days; i++) {
                double n1 = navData.get(i).path("nav").asDouble(0);
                double n2 = navData.get(i + 1).path("nav").asDouble(0);
                returns[i] = n2 > 0 ? (n1 - n2) / n2 : 0;
            }
            double mean = 0;
            for (double r : returns) mean += r;
            mean /= days;
            double var = 0;
            for (double r : returns) var += (r - mean) * (r - mean);
            double std = Math.sqrt(var / days);
            double annRet = mean * 252;
            double annStd = std * Math.sqrt(252);
            return annStd > 0 ? (annRet - 0.065) / annStd : 0;
        } catch (Exception e) { return 0; }
    }

    private double computeReturn(JsonNode navData, int approximateDays) {
        try {
            int idx = Math.min(approximateDays, navData.size() - 1);
            double current = navData.get(0).path("nav").asDouble(0);
            double old = navData.get(idx).path("nav").asDouble(0);
            if (old <= 0 || current <= 0) return 0;
            return (current / old - 1) * 100;
        } catch (Exception e) { return 0; }
    }

    private double compute52wHigh(JsonNode navData) {
        try {
            int days = Math.min(252, navData.size());
            double high = 0;
            for (int i = 0; i < days; i++) {
                double nav = navData.get(i).path("nav").asDouble(0);
                if (nav > high) high = nav;
            }
            return high;
        } catch (Exception e) { return 0; }
    }

    private double compute52wLow(JsonNode navData) {
        try {
            int days = Math.min(252, navData.size());
            double low = Double.MAX_VALUE;
            for (int i = 0; i < days; i++) {
                double nav = navData.get(i).path("nav").asDouble(0);
                if (nav > 0 && nav < low) low = nav;
            }
            return low == Double.MAX_VALUE ? 0 : low;
        } catch (Exception e) { return 0; }
    }

    private int computeConsistency(JsonNode navData) {
        int count = 0;
        for (int w = 0; w < 9; w++) {
            int startIdx = w * 63;  // quarterly-staggered windows
            int endIdx = startIdx + 252;
            if (endIdx >= navData.size()) break;
            try {
                double start = navData.get(endIdx).path("nav").asDouble(0);
                double end = navData.get(startIdx).path("nav").asDouble(0);
                if (start > 0 && end > start) count++;
            } catch (Exception ignored) {}
        }
        return count;
    }

    private record SchemeRef(String code, String name) {}
}
