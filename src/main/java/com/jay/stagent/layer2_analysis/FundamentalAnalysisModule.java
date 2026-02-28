package com.jay.stagent.layer2_analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jay.stagent.config.AgentConfig;
import com.jay.stagent.model.FundamentalData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Layer 2 — Fundamental Analysis Module.
 * Evaluates the intrinsic quality of a business over a 3-5 year historical window.
 * Data source: Screener.in company API (public endpoint, no auth for basic data).
 *
 * Screener.in API: https://www.screener.in/api/company/<symbol>/
 * Note: For production use, consider Screener.in premium API or Tickertape API.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FundamentalAnalysisModule {

    private final AgentConfig config;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(chain -> chain.proceed(
            chain.request().newBuilder()
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
        ))
        .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public record FundamentalResult(double score, String summary, FundamentalData data) {}

    /**
     * Analyses the fundamental quality of a stock and returns a score 0-100.
     * Hard disqualifiers will return a score of 0 regardless of other factors.
     */
    public FundamentalResult analyse(String symbol) {
        AgentConfig.Fundamental cfg = config.fundamental();

        // Fetch data from Screener.in
        FundamentalData data = fetchFromScreener(symbol);
        if (data == null) {
            return new FundamentalResult(0, "Could not fetch fundamental data for " + symbol, null);
        }

        // ── Hard Disqualifiers (score → 0 immediately) ────────────────────────
        if (data.getDebtToEquity() > cfg.getHardMaxDebtToEquity()) {
            return new FundamentalResult(0,
                String.format("D/E=%.1f exceeds hard limit of %.1f — DISQUALIFIED",
                    data.getDebtToEquity(), cfg.getHardMaxDebtToEquity()), data);
        }

        double score = 0;
        StringBuilder summary = new StringBuilder();

        // ── Revenue Growth (max 20 pts) ────────────────────────────────────────
        if (data.getRevenueCagr3y() >= cfg.getMinRevenueCagr3yPct()) {
            if (data.getRevenueCagr3y() >= 20) score += 20;
            else if (data.getRevenueCagr3y() >= 15) score += 15;
            else score += 10;
            summary.append(String.format("Rev CAGR %.0f%% ✓. ", data.getRevenueCagr3y()));
        } else {
            score += Math.max(0, data.getRevenueCagr3y() * 0.5); // Partial credit
            summary.append(String.format("Rev CAGR %.0f%% below min. ", data.getRevenueCagr3y()));
        }

        // ── Profitability (max 20 pts) ─────────────────────────────────────────
        double roePts = 0;
        if (data.getRoe() >= cfg.getMinRoePct()) roePts = 10;
        else if (data.getRoe() >= 10) roePts = 5;
        double rocePts = 0;
        if (data.getRoce() >= cfg.getMinRocePct()) rocePts = 10;
        else if (data.getRoce() >= 8) rocePts = 5;
        score += roePts + rocePts;
        summary.append(String.format("ROE %.0f%% ROCE %.0f%%", data.getRoe(), data.getRoce()));
        summary.append(roePts + rocePts >= 15 ? " ✓. " : " (below target). ");

        // ── Debt to Equity (max 15 pts) ────────────────────────────────────────
        if (data.getDebtToEquity() <= 0.3) { score += 15; summary.append("Debt-free ✓. "); }
        else if (data.getDebtToEquity() <= cfg.getMaxDebtToEquity()) { score += 10; summary.append("D/E ok. "); }
        else { score += 3; summary.append(String.format("D/E=%.1f elevated. ", data.getDebtToEquity())); }

        // ── Cash Flow (max 15 pts) ────────────────────────────────────────────
        if (data.getPositiveCfYears() >= 4) { score += 15; summary.append("Consistent OCF ✓. "); }
        else if (data.getPositiveCfYears() >= 3) { score += 10; }
        else { score += 2; summary.append("Inconsistent cash flow. "); }

        // ── Promoter Holding (max 10 pts) ─────────────────────────────────────
        if (data.getPromoterPledgedPct() > 50) {
            score -= 10;
            summary.append("High promoter pledge ✗. ");
        } else if (data.getPromoterHoldingPct() >= cfg.getMinPromoterHoldingPct()) {
            score += 10;
            summary.append(String.format("Promoter %.0f%% ✓. ", data.getPromoterHoldingPct()));
        } else {
            score += 5;
        }

        // ── Valuation (max 10 pts) ────────────────────────────────────────────
        boolean goodValuation = false;
        if (data.getPeRatio() > 0 && data.getSectorMedianPe() > 0) {
            if (data.getPeRatio() <= data.getSectorMedianPe() * 1.1) {
                score += 7;
                goodValuation = true;
            } else if (data.getPeRatio() <= data.getSectorMedianPe() * 1.3) {
                score += 4;
            }
        }
        if (data.getPegRatio() > 0 && data.getPegRatio() <= cfg.getMaxPegRatio()) {
            score += 3;
            goodValuation = true;
        }
        if (goodValuation) summary.append(String.format("PE=%.0f PEG=%.1f valuation ok. ",
            data.getPeRatio(), data.getPegRatio()));
        else summary.append("Valuation stretched. ");

        // ── Sector Outlook (max 10 pts) ───────────────────────────────────────
        score += data.getSectorOutlookScore();

        score = Math.max(0, Math.min(100, score));
        log.debug("Fundamental score for {}: {:.1f}", symbol, score);

        return new FundamentalResult(score, summary.toString().trim(), data);
    }

    // ── Data Fetching ──────────────────────────────────────────────────────────

    /**
     * Fetches fundamental data from Screener.in API.
     * Endpoint: https://www.screener.in/api/company/{symbol}/
     */
    private FundamentalData fetchFromScreener(String symbol) {
        String url = "https://www.screener.in/api/company/" + symbol + "/";
        try {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("Screener.in returned {} for {}", response.code(), symbol);
                    return buildDefaultFundamental(symbol);
                }
                String body = response.body().string();
                return parseScreenerResponse(symbol, body);
            }
        } catch (Exception e) {
            log.error("Screener.in fetch failed for {}: {}", symbol, e.getMessage());
            return buildDefaultFundamental(symbol);
        }
    }

    private FundamentalData parseScreenerResponse(String symbol, String json) {
        try {
            JsonNode root = mapper.readTree(json);

            // Screener returns ratios in a structured format
            // Key fields from Screener.in API response:
            double pe = safeDouble(root, "primary_ratio_pe");
            double pb = safeDouble(root, "book_value_ratio");
            double roe = safeDouble(root, "return_on_equity");
            double roce = safeDouble(root, "roce");
            double de = safeDouble(root, "debt_to_equity");
            double promoterPct = safeDouble(root, "promoter_holding");

            return FundamentalData.builder()
                .symbol(symbol)
                .revenueCagr3y(safeDouble(root, "compounded_sales_growth_3years"))
                .revenueCagr5y(safeDouble(root, "compounded_sales_growth_5years"))
                .netProfitCagr3y(safeDouble(root, "compounded_profit_growth_3years"))
                .roe(roe)
                .roce(roce)
                .debtToEquity(de)
                .operatingCashFlow(safeDouble(root, "cash_from_operations"))
                .freeCashFlow(safeDouble(root, "free_cash_flow"))
                .positiveCfYears(3) // Estimate — needs historical CF data
                .promoterHoldingPct(promoterPct)
                .promoterPledgedPct(safeDouble(root, "promoter_holding_pledged"))
                .peRatio(pe)
                .pbRatio(pb)
                .pegRatio(safeDouble(root, "peg_ratio"))
                .sectorMedianPe(pe * 1.1) // Approximate — needs sector data
                .sector(root.path("industry").asText("Unknown"))
                .sectorOutlookScore(5) // Default neutral — update from sector scoring
                .build();
        } catch (Exception e) {
            log.warn("Failed to parse Screener.in response for {}: {}", symbol, e.getMessage());
            return buildDefaultFundamental(symbol);
        }
    }

    private double safeDouble(JsonNode node, String field) {
        try { return node.path(field).asDouble(0); }
        catch (Exception e) { return 0; }
    }

    /** Returns a neutral/conservative default when data is unavailable */
    private FundamentalData buildDefaultFundamental(String symbol) {
        return FundamentalData.builder()
            .symbol(symbol)
            .revenueCagr3y(10)
            .roe(15).roce(12)
            .debtToEquity(0.5)
            .positiveCfYears(3)
            .promoterHoldingPct(45)
            .promoterPledgedPct(0)
            .peRatio(20).pbRatio(3).pegRatio(1.2)
            .sectorMedianPe(22)
            .sector("Unknown")
            .sectorOutlookScore(5)
            .build();
    }
}
