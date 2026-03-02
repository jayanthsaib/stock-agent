package com.jay.stagent.layer2_analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Layer 2 — News & Sentiment Service.
 * Fetches recent headlines for a stock from Yahoo Finance and scores them
 * using positive/negative keyword lists. Returns a 0-100 sentiment score
 * (50 = neutral, >60 = positive, <40 = negative).
 *
 * This score is used as a ±5 point adjustment on the fundamental score
 * to factor in near-term news sentiment without overriding fundamentals.
 */
@Slf4j
@Service
public class NewsService {

    private static final String NEWS_API =
        "https://query1.finance.yahoo.com/v1/finance/search?q=%s.NS" +
        "&lang=en-US&region=IN&quotesCount=0&newsCount=8&enableFuzzyQuery=false";

    private static final List<String> POSITIVE = List.of(
        "profit", "growth", "beat", "record", "acquisition", "order", "win",
        "upgrade", "dividend", "buyback", "strong", "positive", "surge", "rally",
        "launch", "expansion", "highest", "robust", "momentum", "outperform",
        "contract", "deal", "approved", "milestone", "gains", "rises", "jumps"
    );

    private static final List<String> NEGATIVE = List.of(
        "loss", "fraud", "scam", "recall", "downgrade", "miss", "below",
        "penalty", "investigation", "decline", "weak", "concern", "falls",
        "drops", "crisis", "default", "lawsuit", "resign", "slump", "sell",
        "warning", "debt", "risk", "cut", "probe", "complaint", "underperform"
    );

    // Cap concurrent calls to avoid rate-limiting Yahoo Finance
    private static final Semaphore rateLimiter = new Semaphore(3);

    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Returns a sentiment score 0-100 for the given NSE symbol.
     * 50 = neutral. Returns 50 if news is unavailable.
     */
    public double getSentimentScore(String symbol) {
        try {
            rateLimiter.acquire();
            try {
                return fetchAndScore(symbol);
            } finally {
                rateLimiter.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 50.0;
        }
    }

    private double fetchAndScore(String symbol) {
        String url = String.format(NEWS_API, symbol);
        try {
            Request req = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .addHeader("Accept", "application/json")
                .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return 50.0;

                JsonNode root = mapper.readTree(resp.body().string());
                JsonNode news = root.path("news");
                if (!news.isArray() || news.isEmpty()) return 50.0;

                int positive = 0, negative = 0;
                for (JsonNode item : news) {
                    String title = item.path("title").asText("").toLowerCase(Locale.ROOT);
                    for (String kw : POSITIVE) if (title.contains(kw)) positive++;
                    for (String kw : NEGATIVE) if (title.contains(kw)) negative++;
                }

                // Score: 50 baseline ± 5 per net keyword match, clamped to 0-100
                double score = 50.0 + (positive - negative) * 5.0;
                score = Math.max(0, Math.min(100, score));
                log.debug("News sentiment for {}: +{}/-{} → score={}", symbol, positive, negative, score);
                return score;
            }
        } catch (Exception e) {
            log.debug("News fetch failed for {}: {}", symbol, e.getMessage());
            return 50.0; // neutral fallback
        }
    }
}
