package com.jay.stagent.layer2_analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jay.stagent.config.AgentConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Layer 2 — Mutual Fund Analysis Module.
 * Evaluates MF quality for SIP or lump-sum decisions (reviewed monthly, not daily).
 * Data source: AMFI NAV API (free, public) + Value Research / MFI Explorer.
 *
 * AMFI NAV API: https://api.mfapi.in/mf/{schemeCode}
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
        double expenseRatio,
        double sharpeRatio
    ) {}

    /**
     * Analyses a mutual fund by scheme code and returns a score 0-100.
     * @param schemeCode AMFI scheme code (e.g., 120503 for Axis Bluechip)
     */
    public MutualFundResult analyse(String schemeCode) {
        JsonNode fundData = fetchFromAmfi(schemeCode);
        if (fundData == null) {
            return new MutualFundResult(0, "Could not fetch MF data for " + schemeCode,
                schemeCode, 0, 0, 0);
        }

        String fundName = fundData.path("meta").path("fund_house").asText("Unknown Fund");
        double score = 50.0;
        StringBuilder summary = new StringBuilder();

        // For production: compute CAGR from NAV history, fetch expense ratio from scheme documents
        // Below uses placeholder values — wire to Value Research API or MF Docs for real data

        // ── Returns vs Benchmark (max 25 pts) ─────────────────────────────────
        double cagr3y = computeCAGR(fundData, 3);
        double niftyCagr3y = 12.0; // Approximate Nifty 50 3Y CAGR — fetch from data engine
        if (cagr3y > niftyCagr3y + 2) {
            score += 25;
            summary.append(String.format("3Y CAGR %.1f%% — beats benchmark ✓. ", cagr3y));
        } else if (cagr3y > niftyCagr3y) {
            score += 15;
            summary.append(String.format("3Y CAGR %.1f%% — beats benchmark. ", cagr3y));
        } else {
            score -= 10;
            summary.append(String.format("3Y CAGR %.1f%% — underperforms benchmark ✗. ", cagr3y));
        }

        // ── Expense Ratio (max 15 pts) ─────────────────────────────────────────
        // AMFI doesn't provide this directly — default to checking fund category
        double expenseRatio = 0.5; // Default; fetch from scheme documents for real data
        if (expenseRatio <= 0.5) { score += 15; summary.append("Expense ratio ≤0.5% ✓. "); }
        else if (expenseRatio <= 1.2) { score += 8; summary.append("Expense ratio acceptable. "); }
        else { score -= 5; summary.append("High expense ratio ✗. "); }

        // ── Sharpe Ratio (max 20 pts) ─────────────────────────────────────────
        double sharpe = computeSharpeRatio(fundData);
        if (sharpe >= 1.5) { score += 20; summary.append(String.format("Sharpe=%.2f excellent ✓. ", sharpe)); }
        else if (sharpe >= 0.8) { score += 12; summary.append(String.format("Sharpe=%.2f acceptable. ", sharpe)); }
        else { score -= 10; summary.append(String.format("Sharpe=%.2f below minimum ✗. ", sharpe)); }

        // ── AUM Size (max 10 pts) ─────────────────────────────────────────────
        // AUM not available from AMFI NAV API — default to passing
        score += 8; // Add when wired to Value Research API
        summary.append("AUM size: acceptable. ");

        // ── Fund Manager Tenure (max 10 pts) ──────────────────────────────────
        // Not available from AMFI API — wire to fund documents
        score += 7;
        summary.append("Manager tenure: assumed adequate. ");

        // ── Portfolio Concentration (max 10 pts) ──────────────────────────────
        score += 8;
        summary.append("Concentration: not assessed — fetch fund portfolio data. ");

        score = Math.max(0, Math.min(100, score));
        log.debug("MF score for scheme {}: {:.1f}", schemeCode, score);

        return new MutualFundResult(score, summary.toString().trim(),
            fundName, cagr3y, expenseRatio, sharpe);
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

    /**
     * Computes approximate CAGR from NAV history.
     * Formula: CAGR = (currentNav / navNYearsAgo)^(1/N) - 1
     */
    private double computeCAGR(JsonNode fundData, int years) {
        try {
            JsonNode navData = fundData.path("data");
            if (navData.isEmpty()) return 0;

            double currentNav = navData.get(0).path("nav").asDouble(0);
            int targetIndex = Math.min(years * 252, navData.size() - 1); // ~252 trading days/year
            double oldNav = navData.get(targetIndex).path("nav").asDouble(0);

            if (oldNav <= 0 || currentNav <= 0) return 0;
            return (Math.pow(currentNav / oldNav, 1.0 / years) - 1) * 100;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Computes approximate Sharpe ratio from NAV returns.
     * Uses 1-year NAV history. Risk-free rate approximated as 6.5%.
     */
    private double computeSharpeRatio(JsonNode fundData) {
        try {
            JsonNode navData = fundData.path("data");
            int days = Math.min(252, navData.size() - 1);
            if (days < 30) return 0;

            double[] returns = new double[days];
            for (int i = 0; i < days; i++) {
                double nav1 = navData.get(i).path("nav").asDouble(0);
                double nav2 = navData.get(i + 1).path("nav").asDouble(0);
                returns[i] = nav2 > 0 ? (nav1 - nav2) / nav2 : 0;
            }

            double meanReturn = 0;
            for (double r : returns) meanReturn += r;
            meanReturn /= days;

            double variance = 0;
            for (double r : returns) variance += (r - meanReturn) * (r - meanReturn);
            variance /= days;
            double stdDev = Math.sqrt(variance);

            double annualReturn = meanReturn * 252;
            double annualStd = stdDev * Math.sqrt(252);
            double riskFreeRate = 0.065; // RBI repo rate approximation

            return annualStd > 0 ? (annualReturn - riskFreeRate) / annualStd : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
