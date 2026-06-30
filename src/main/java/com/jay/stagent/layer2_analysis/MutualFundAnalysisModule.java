package com.jay.stagent.layer2_analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jay.stagent.config.AgentConfig;
import com.jay.stagent.model.MFSchemeData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Layer 2 — Mutual Fund Analysis Module.
 * Evaluates MF quality for SIP or lump-sum decisions.
 * Data source: AMFI NAV API via mfapi.in (pre-fetched by MFDataIngestionEngine).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MutualFundAnalysisModule {

    private final AgentConfig config;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public record MutualFundResult(
        double score,
        String summary,
        String fundName,
        double cagr3y,
        double sharpeRatio,
        double return1y,
        double return6m,
        double return3m,
        int consistencyCount
    ) {}

    /**
     * Analyses a mutual fund from pre-fetched MFSchemeData.
     * Called by MFSignalGenerator with data already in memory.
     */
    public MutualFundResult analyse(MFSchemeData scheme, double benchmarkCagr3y) {
        double score = 50.0;
        StringBuilder summary = new StringBuilder();

        String fundName = scheme.getSchemeName();

        // ── Returns vs Benchmark (max 25 pts) ─────────────────────────────────
        double cagr3y = scheme.getCagr3y();
        if (cagr3y > benchmarkCagr3y + 2) {
            score += 25;
            summary.append(String.format("3Y CAGR %.1f%% — beats benchmark ✓. ", cagr3y));
        } else if (cagr3y > benchmarkCagr3y) {
            score += 15;
            summary.append(String.format("3Y CAGR %.1f%% — beats benchmark. ", cagr3y));
        } else {
            score -= 10;
            summary.append(String.format("3Y CAGR %.1f%% — underperforms benchmark ✗. ", cagr3y));
        }

        // ── Direct Plan / Expense Ratio (max 15 pts) ─────────────────────────
        if (scheme.isDirect()) {
            score += 15;
            summary.append("Direct plan (lower expense ratio) ✓. ");
        } else {
            summary.append("Regular plan (higher expense ratio) ✗. ");
        }

        // ── Sharpe Ratio (max 20 pts) ─────────────────────────────────────────
        double sharpe = scheme.getSharpeRatio();
        if (sharpe >= 1.5)      { score += 20; summary.append(String.format("Sharpe=%.2f excellent ✓. ", sharpe)); }
        else if (sharpe >= 0.8) { score += 12; summary.append(String.format("Sharpe=%.2f acceptable. ", sharpe)); }
        else                    { score -= 10; summary.append(String.format("Sharpe=%.2f below minimum ✗. ", sharpe)); }

        // ── NAV Momentum (replaces AUM, max 10 pts) ───────────────────────────
        double r1y = scheme.getReturn1y(), r6m = scheme.getReturn6m(), r3m = scheme.getReturn3m();
        if (r1y > 15 && r6m > 7 && r3m > 3)    { score += 10; summary.append("Strong momentum ✓. "); }
        else if (r1y > 10 && r6m > 4)            { score += 7;  summary.append("Good momentum. "); }
        else if (r1y > 0 && r6m > 0)             { score += 4;  summary.append("Mild positive momentum. "); }
        else                                      { score -= 5;  summary.append("Negative recent momentum ✗. "); }

        // ── Consistency (replaces manager tenure, max 10 pts) ────────────────
        int c = scheme.getConsistencyCount();
        if (c >= 8)      { score += 10; summary.append(String.format("Consistent: %d/9 positive periods ✓. ", c)); }
        else if (c >= 6) { score += 7;  summary.append(String.format("Consistent: %d/9 positive periods. ", c)); }
        else if (c >= 4) { score += 3;  summary.append(String.format("Inconsistent: %d/9 positive periods. ", c)); }
        else             { score -= 5;  summary.append(String.format("Poor consistency: %d/9 positive periods ✗. ", c)); }

        // ── Category Score (replaces concentration, max 10 pts) ──────────────
        String cat = scheme.getCategory() != null ? scheme.getCategory().toLowerCase() : "";
        if (cat.contains("flexi cap") || cat.contains("multi cap")) { score += 10; summary.append("Diversified category ✓. "); }
        else if (cat.contains("large cap"))                          { score += 8;  summary.append("Large cap category. "); }
        else if (cat.contains("elss"))                               { score += 7;  summary.append("ELSS (tax benefit). "); }
        else if (cat.contains("mid cap"))                            { score += 6;  summary.append("Mid cap category. "); }
        else if (cat.contains("small cap"))                          { score += 5;  summary.append("Small cap — higher risk. "); }
        else                                                          { score += 3;  summary.append("Sector/thematic fund. "); }

        score = Math.max(0, Math.min(100, score));
        log.debug("MF score for {}: {}", scheme.getSchemeCode(), score);

        return new MutualFundResult(score, summary.toString().trim(),
            fundName, cagr3y, sharpe, r1y, r6m, r3m, c);
    }

    /**
     * Analyses a mutual fund by scheme code directly (standalone / legacy use).
     */
    public MutualFundResult analyse(String schemeCode) {
        JsonNode fundData = fetchFromAmfi(schemeCode);
        if (fundData == null) {
            return new MutualFundResult(0, "Could not fetch MF data for " + schemeCode,
                schemeCode, 0, 0, 0, 0, 0, 0);
        }

        String fundName = fundData.path("meta").path("fund_house").asText("Unknown Fund");
        JsonNode navData = fundData.path("data");

        MFSchemeData scheme = MFSchemeData.builder()
            .schemeCode(schemeCode)
            .schemeName(fundName)
            .category(fundData.path("meta").path("scheme_category").asText(""))
            .isDirect(fundName.toLowerCase().contains("direct"))
            .currentNav(navData.isEmpty() ? 0 : navData.get(0).path("nav").asDouble(0))
            .cagr3y(computeCAGR(navData, 3))
            .sharpeRatio(computeSharpe(navData))
            .return1y(computeReturn(navData, 252))
            .return6m(computeReturn(navData, 126))
            .return3m(computeReturn(navData, 63))
            .consistencyCount(computeConsistency(navData))
            .build();

        double benchmark = config.mutualFunds().getBenchmarkCagr3yPct();
        return analyse(scheme, benchmark);
    }

    // ── AMFI API ───────────────────────────────────────────────────────────────

    private JsonNode fetchFromAmfi(String schemeCode) {
        String url = "https://api.mfapi.in/mf/" + schemeCode;
        try {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                return mapper.readTree(response.body().string());
            }
        } catch (Exception e) {
            log.error("AMFI API failed for scheme {}: {}", schemeCode, e.getMessage());
            return null;
        }
    }

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

    private int computeConsistency(JsonNode navData) {
        int count = 0;
        for (int w = 0; w < 9; w++) {
            int startIdx = w * 63;
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
}
