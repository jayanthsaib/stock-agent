package com.jay.stagent.layer2_analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jay.stagent.config.AgentConfig;
import com.jay.stagent.model.FundamentalData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
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
    // Simple in-memory CookieJar — stores all cookies and matches them by URL
    // using OkHttp's own Cookie.matches(), equivalent to curl -c/-b.
    private final List<Cookie> cookieStore = new CopyOnWriteArrayList<>();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .cookieJar(new CookieJar() {
            @Override public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                cookieStore.addAll(cookies);
            }
            @Override public List<Cookie> loadForRequest(HttpUrl url) {
                List<Cookie> matched = new ArrayList<>();
                for (Cookie c : cookieStore) { if (c.matches(url)) matched.add(c); }
                return matched;
            }
        })
        .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile String yahooCrumb = null;

    // Cap concurrent Yahoo Finance calls to avoid rate-limiting when bulk analysis runs
    private static final Semaphore yahooRateLimiter = new Semaphore(5);

    public record FundamentalResult(double score, String summary, FundamentalData data) {}

    /**
     * Analyses the fundamental quality of a stock and returns a score 0-100.
     * Hard disqualifiers will return a score of 0 regardless of other factors.
     */
    public FundamentalResult analyse(String symbol) {
        AgentConfig.Fundamental cfg = config.fundamental();

        // Fetch data from Yahoo Finance
        FundamentalData data = fetchFromYahoo(symbol);
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
     * Obtains a Yahoo Finance session cookie (from fc.yahoo.com) and crumb
     * (from query2.finance.yahoo.com/v1/test/getcrumb).  Both are cached in
     * volatile fields and reused across calls.  Called lazily before the first
     * quoteSummary request and on 401 to refresh.
     */
    private synchronized void initYahooCredentials() {
        if (yahooCrumb != null) return; // another thread already refreshed
        try {
            // Step 1 — visit fc.yahoo.com; the CookieJar stores all Set-Cookie headers
            // automatically (including those from redirect chains), just like curl -c/-b.
            Request fcReq = new Request.Builder()
                .url("https://fc.yahoo.com")
                .addHeader("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get().build();
            try (Response fcResp = httpClient.newCall(fcReq).execute()) {
                log.debug("fc.yahoo.com responded with HTTP {}", fcResp.code());
            }

            // Step 2 — get crumb; cookies are sent automatically by the CookieJar
            Request crumbReq = new Request.Builder()
                .url("https://query2.finance.yahoo.com/v1/test/getcrumb")
                .addHeader("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Accept", "text/plain")
                .get().build();
            try (Response crumbResp = httpClient.newCall(crumbReq).execute()) {
                if (!crumbResp.isSuccessful() || crumbResp.body() == null) {
                    log.warn("Yahoo Finance: crumb request returned {}", crumbResp.code());
                    return;
                }
                String crumb = crumbResp.body().string().trim();
                if (crumb.isEmpty() || crumb.startsWith("{")) {
                    log.warn("Yahoo Finance: invalid crumb received: {}", crumb);
                    return;
                }
                yahooCrumb = crumb;
                log.info("Yahoo Finance credentials initialised (crumb length={})", crumb.length());
            }
        } catch (Exception e) {
            log.error("Yahoo Finance credential init failed: {}", e.getMessage());
        }
    }

    /**
     * Fetches fundamental data from Yahoo Finance quoteSummary API using crumb auth.
     * Endpoint: https://query2.finance.yahoo.com/v10/finance/quoteSummary/{symbol}.NS
     */
    private FundamentalData fetchFromYahoo(String symbol) {
        if (yahooCrumb == null) initYahooCredentials();
        if (yahooCrumb == null) {
            log.warn("Yahoo Finance crumb unavailable — returning defaults for {}", symbol);
            return buildDefaultFundamental(symbol);
        }
        try {
            yahooRateLimiter.acquire();
            try {
                return doYahooFetch(symbol, false);
            } finally {
                yahooRateLimiter.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return buildDefaultFundamental(symbol);
        }
    }

    private FundamentalData doYahooFetch(String symbol, boolean isRetry) {
        try {
            String crumb = URLEncoder.encode(yahooCrumb, StandardCharsets.UTF_8);

            // Split into two 2-module calls. A single 4-module request causes Yahoo
            // to silently omit debtToEquity and other fields in defaultKeyStatistics.
            JsonNode finNode = callQuoteSummary(symbol, "financialData,defaultKeyStatistics", crumb);
            if (finNode == null && !isRetry) {
                log.warn("Yahoo Finance error for {} — refreshing crumb and retrying", symbol);
                yahooCrumb = null;
                cookieStore.clear();
                initYahooCredentials();
                if (yahooCrumb == null) return buildDefaultFundamental(symbol);
                return doYahooFetch(symbol, true);
            }
            if (finNode == null) return buildDefaultFundamental(symbol);

            JsonNode valNode = callQuoteSummary(symbol, "summaryDetail,assetProfile", crumb);
            return parseYahooResponse(symbol, finNode, valNode != null ? valNode : mapper.createObjectNode());
        } catch (Exception e) {
            log.error("Yahoo Finance fetch failed for {}: {}", symbol, e.getMessage());
            return buildDefaultFundamental(symbol);
        }
    }

    /** Makes one quoteSummary call and returns the first result node, or null on failure. */
    private JsonNode callQuoteSummary(String symbol, String modules, String encodedCrumb) {
        String url = "https://query2.finance.yahoo.com/v10/finance/quoteSummary/" + symbol
            + ".NS?modules=" + modules + "&crumb=" + encodedCrumb;
        try {
            Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Accept", "application/json")
                .get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("Yahoo Finance [{}] returned {} for {}", modules, response.code(), symbol);
                    return null;
                }
                JsonNode root = mapper.readTree(response.body().string());
                JsonNode result = root.path("quoteSummary").path("result");
                return (result.isArray() && !result.isEmpty()) ? result.get(0) : null;
            }
        } catch (Exception e) {
            log.warn("Yahoo Finance [{}] call failed for {}: {}", modules, symbol, e.getMessage());
            return null;
        }
    }

    private FundamentalData parseYahooResponse(String symbol, JsonNode finNode, JsonNode valNode) {
        try {
            JsonNode financial = finNode.path("financialData");
            JsonNode keyStats  = finNode.path("defaultKeyStatistics");
            JsonNode summary   = valNode.path("summaryDetail");
            JsonNode profile   = valNode.path("assetProfile");

            // D/E: Yahoo stores as percentage (35.651 → D/E ratio of 0.356).
            // financialData also carries debtToEquity — try both modules.
            double deRaw = keyStats.path("debtToEquity").path("raw").asDouble(-1);
            if (deRaw <= 0) deRaw = financial.path("debtToEquity").path("raw").asDouble(-1);
            double de = (deRaw > 0) ? deRaw / 100 : 0.5; // 0.5 conservative default if absent

            // ROE: Yahoo's financialData.returnOnEquity is often absent for Indian stocks.
            // Fallback: approximate from trailingEps / bookValue (both per-share).
            double roeDirectRaw = financial.path("returnOnEquity").path("raw").asDouble(0) * 100;
            double roe;
            if (roeDirectRaw != 0) {
                roe = roeDirectRaw;
            } else {
                double eps = keyStats.path("trailingEps").path("raw").asDouble(0);
                double bv  = keyStats.path("bookValue").path("raw").asDouble(0);
                roe = (eps > 0 && bv > 0) ? (eps / bv) * 100 : 0;
            }

            // ROCE: approximate from returnOnAssets if available; else use ROE * 0.8
            double roceRaw = financial.path("returnOnAssets").path("raw").asDouble(0) * 150;
            double roce    = roceRaw > 0 ? roceRaw : roe * 0.8;

            double pe        = summary.path("trailingPE").path("raw").asDouble(0);
            double pb        = summary.path("priceToBook").path("raw").asDouble(0);
            double peg       = keyStats.path("pegRatio").path("raw").asDouble(0);
            double revGrowth = financial.path("revenueGrowth").path("raw").asDouble(0) * 100;
            double ocf       = financial.path("operatingCashflow").path("raw").asDouble(0);
            double fcf       = financial.path("freeCashflow").path("raw").asDouble(0);

            // OCF proxy: if operatingCashflow field is absent, infer from gross profit.
            // A profitable business with gross profit almost certainly has positive OCF.
            boolean positiveOcf = ocf > 0
                || financial.path("grossProfits").path("raw").asDouble(0) > 0;

            String sector = profile.path("sector").asText("Unknown");

            log.info("Yahoo parsed {}: ROE={}% D/E={} PE={} revGrowth={}% positiveOcf={} sector={}",
                symbol, Math.round(roe), Math.round(de * 1000) / 1000.0,
                Math.round(pe * 10) / 10.0, Math.round(revGrowth * 10) / 10.0,
                positiveOcf, sector);

            return FundamentalData.builder()
                .symbol(symbol)
                .revenueCagr3y(revGrowth > 0 ? revGrowth : 8)
                .revenueCagr5y(revGrowth)
                .netProfitCagr3y(financial.path("earningsGrowth").path("raw").asDouble(0) * 100)
                .roe(roe)
                .roce(roce)
                .debtToEquity(de)
                .operatingCashFlow(ocf)
                .freeCashFlow(fcf)
                .positiveCfYears(positiveOcf ? 4 : 2)
                .promoterHoldingPct(50)
                .promoterPledgedPct(0)
                .peRatio(pe)
                .pbRatio(pb)
                .pegRatio(peg)
                .sectorMedianPe(pe > 0 ? pe * 1.1 : 22)
                .sector(sector)
                .sectorOutlookScore(5)
                .build();
        } catch (Exception e) {
            log.warn("Failed to parse Yahoo Finance response for {}: {}", symbol, e.getMessage());
            return buildDefaultFundamental(symbol);
        }
    }

    /**
     * Fetches fundamental data from Screener.in API (kept as reference).
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
