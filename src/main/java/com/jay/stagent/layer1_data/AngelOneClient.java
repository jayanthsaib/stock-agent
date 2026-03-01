package com.jay.stagent.layer1_data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jay.stagent.config.AgentConfig;
import com.jay.stagent.model.OHLCVBar;
import com.jay.stagent.model.StockData;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Layer 1 — Angel One SmartAPI REST client.
 * Handles authentication, live quotes, historical OHLCV, order placement,
 * and portfolio fetching directly via OkHttp (no SDK dependency).
 *
 * API base: https://apiconnect.angelbroking.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AngelOneClient {

    private static final String BASE_URL = "https://apiconnect.angelbroking.com";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final DateTimeFormatter CANDLE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AgentConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private OkHttpClient http;
    private String jwtToken;
    private String feedToken;
    private String refreshToken;
    private LocalDateTime tokenExpiry;

    @PostConstruct
    public void init() {
        this.http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    }

    // ── Authentication ─────────────────────────────────────────────────────────

    /**
     * Generates a new SmartAPI session. Must be called once at startup and refreshed daily.
     * Uses client-id + MPIN + TOTP for authentication.
     */
    public boolean login() {
        AgentConfig.Broker broker = config.broker();
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("clientcode", broker.getClientId());
            body.put("password", broker.getMpin());
            body.put("totp", generateTotp(broker.getTotpSecret()));

            String responseJson = post("/rest/auth/angelbroking/user/v1/loginByPassword", body.toString(), false);
            JsonNode root = objectMapper.readTree(responseJson);

            if (root.path("status").asBoolean()) {
                JsonNode data = root.path("data");
                this.jwtToken     = data.path("jwtToken").asText();
                this.feedToken    = data.path("feedToken").asText();
                this.refreshToken = data.path("refreshToken").asText();
                this.tokenExpiry  = LocalDateTime.now().plusHours(8);
                log.info("Angel One login successful for client: {}", broker.getClientId());
                return true;
            } else {
                log.error("Angel One login failed: {}", root.path("message").asText());
                return false;
            }
        } catch (Exception e) {
            log.error("Angel One login exception: {}", e.getMessage());
            return false;
        }
    }

    public boolean isAuthenticated() {
        return jwtToken != null && !jwtToken.isBlank() &&
               tokenExpiry != null && LocalDateTime.now().isBefore(tokenExpiry);
    }

    public void ensureAuthenticated() {
        if (!isAuthenticated()) {
            log.info("Session expired or missing — re-authenticating with Angel One");
            login();
        }
    }

    // ── Live Quotes ────────────────────────────────────────────────────────────

    /**
     * Fetches live market quote for a list of symbols.
     * @param exchange  NSE or BSE
     * @param symbolTokens Angel One token IDs for each symbol
     */
    public JsonNode getQuote(String exchange, List<String> symbolTokens) {
        ensureAuthenticated();
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("mode", "FULL");
            ObjectNode data = body.putObject("exchangeTokens");
            data.putArray(exchange).addAll(
                symbolTokens.stream()
                    .map(t -> objectMapper.getNodeFactory().textNode(t))
                    .toList()
            );
            String response = post("/rest/secure/angelbroking/market/v1/quote", body.toString(), true);
            JsonNode root = objectMapper.readTree(response);
            if (!root.path("status").asBoolean()) {
                log.warn("getQuote returned status=false for {} tokens on {}: {}",
                    symbolTokens.size(), exchange, root.path("message").asText());
            }
            JsonNode dataNode = root.path("data");
            // Return empty object if data is null/missing so callers can safely path into it
            return (dataNode.isNull() || dataNode.isMissingNode())
                ? objectMapper.createObjectNode() : dataNode;
        } catch (Exception e) {
            log.error("getQuote failed for {}: {}", symbolTokens.size(), e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    // ── Historical OHLCV ───────────────────────────────────────────────────────

    /**
     * Fetches historical OHLCV candle data.
     * @param symbolToken Angel One instrument token
     * @param exchange    NSE or BSE
     * @param interval    ONE_DAY, ONE_WEEK, ONE_MONTH, etc.
     * @param fromDate    format: "yyyy-MM-dd HH:mm"
     * @param toDate      format: "yyyy-MM-dd HH:mm"
     */
    public List<OHLCVBar> getHistoricalData(String symbolToken, String exchange,
                                             String interval, String fromDate, String toDate) {
        ensureAuthenticated();
        List<OHLCVBar> bars = new ArrayList<>();
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("exchange", exchange);
            body.put("symboltoken", symbolToken);
            body.put("interval", interval);
            body.put("fromdate", fromDate);
            body.put("todate", toDate);

            String response = post("/rest/secure/angelbroking/historical/v1/getCandleData", body.toString(), true);
            JsonNode root = objectMapper.readTree(response);

            if (root.path("status").asBoolean()) {
                JsonNode candleData = root.path("data");
                for (JsonNode candle : candleData) {
                    // Each candle: [timestamp, open, high, low, close, volume]
                    bars.add(OHLCVBar.builder()
                        .timestamp(LocalDateTime.parse(candle.get(0).asText(), DateTimeFormatter.ISO_DATE_TIME))
                        .open(candle.get(1).asDouble())
                        .high(candle.get(2).asDouble())
                        .low(candle.get(3).asDouble())
                        .close(candle.get(4).asDouble())
                        .volume(candle.get(5).asLong())
                        .build());
                }
            } else {
                log.warn("Historical data fetch failed: {}", root.path("message").asText());
            }
        } catch (Exception e) {
            log.error("getHistoricalData failed for token {}: {}", symbolToken, e.getMessage());
        }
        return bars;
    }

    // ── Order Placement ────────────────────────────────────────────────────────

    /**
     * Places a LIMIT order on Angel One. Returns the broker order ID, or null on failure.
     */
    public String placeOrder(String symbolToken, String exchange, String symbol,
                             String transactionType, int quantity, double price) {
        ensureAuthenticated();
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("variety", "NORMAL");
            body.put("tradingsymbol", symbol);
            body.put("symboltoken", symbolToken);
            body.put("transactiontype", transactionType); // BUY or SELL
            body.put("exchange", exchange);
            body.put("ordertype", "LIMIT");
            body.put("producttype", "DELIVERY");
            body.put("duration", "DAY");
            body.put("price", price);
            body.put("squareoff", "0");
            body.put("stoploss", "0");
            body.put("quantity", quantity);

            String response = post("/rest/secure/angelbroking/order/v1/placeOrder", body.toString(), true);
            JsonNode root = objectMapper.readTree(response);

            if (root.path("status").asBoolean()) {
                String orderId = root.path("data").path("orderid").asText();
                log.info("Order placed successfully: {} {} {} @ ₹{} → orderId={}",
                    transactionType, quantity, symbol, price, orderId);
                return orderId;
            } else {
                log.error("Order placement failed: {}", root.path("message").asText());
                return null;
            }
        } catch (Exception e) {
            log.error("placeOrder exception for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    // ── Portfolio / Positions ──────────────────────────────────────────────────

    /** Fetches current open positions from Angel One */
    public JsonNode getPositions() {
        ensureAuthenticated();
        try {
            String response = get("/rest/secure/angelbroking/order/v1/getPosition");
            return objectMapper.readTree(response).path("data");
        } catch (Exception e) {
            log.error("getPositions failed: {}", e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    /** Fetches portfolio holdings (long-term delivery positions) */
    public JsonNode getHoldings() {
        ensureAuthenticated();
        try {
            String response = get("/rest/secure/angelbroking/portfolio/v1/getHolding");
            return objectMapper.readTree(response).path("data");
        } catch (Exception e) {
            log.error("getHoldings failed: {}", e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    /** Fetches available cash balance */
    public double getAvailableCash() {
        ensureAuthenticated();
        try {
            String response = get("/rest/secure/angelbroking/user/v1/getRMS");
            JsonNode root = objectMapper.readTree(response);
            return root.path("data").path("availablecash").asDouble(0);
        } catch (Exception e) {
            log.error("getAvailableCash failed: {}", e.getMessage());
            return 0;
        }
    }

    // ── HTTP Helpers ───────────────────────────────────────────────────────────

    private String post(String path, String jsonBody, boolean authenticated) throws IOException {
        Request.Builder builder = new Request.Builder()
            .url(BASE_URL + path)
            .post(RequestBody.create(jsonBody, JSON))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("X-UserType", "USER")
            .addHeader("X-SourceID", "WEB")
            .addHeader("X-ClientLocalIP", "127.0.0.1")
            .addHeader("X-ClientPublicIP", "127.0.0.1")
            .addHeader("X-MACAddress", "00:00:00:00:00:00")
            .addHeader("X-PrivateKey", config.broker().getApiKey());

        if (authenticated && jwtToken != null) {
            builder.addHeader("Authorization", "Bearer " + jwtToken);
        }

        try (Response response = http.newCall(builder.build()).execute()) {
            return response.body() != null ? response.body().string() : "{}";
        }
    }

    private String get(String path) throws IOException {
        Request request = new Request.Builder()
            .url(BASE_URL + path)
            .get()
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("X-UserType", "USER")
            .addHeader("X-SourceID", "WEB")
            .addHeader("X-ClientLocalIP", "127.0.0.1")
            .addHeader("X-ClientPublicIP", "127.0.0.1")
            .addHeader("X-MACAddress", "00:00:00:00:00:00")
            .addHeader("X-PrivateKey", config.broker().getApiKey())
            .addHeader("Authorization", "Bearer " + jwtToken)
            .build();

        try (Response response = http.newCall(request).execute()) {
            return response.body() != null ? response.body().string() : "{}";
        }
    }

    /**
     * Generates a TOTP code from a secret.
     * Supports both base32 secrets and UUID/hex format secrets (as provided by Angel One).
     * Uses standard TOTP algorithm (RFC 6238, 30-second window, SHA1, 6 digits).
     */
    private String generateTotp(String secret) {
        try {
            byte[] key;
            // UUID-hex format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx (32 hex chars + 4 hyphens)
            String stripped = secret.replace("-", "");
            if (stripped.matches("[0-9a-fA-F]{32}")) {
                // Decode as hex bytes directly
                key = hexDecode(stripped);
                log.debug("TOTP: using UUID-hex key format");
            } else {
                // Treat as base32
                key = base32Decode(secret.toUpperCase().replaceAll("[^A-Z2-7]", ""));
                log.debug("TOTP: using base32 key format");
            }
            long timeStep = System.currentTimeMillis() / 1000L / 30L;

            // HMAC-SHA1
            byte[] timeBytes = longToBytes(timeStep);
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(timeBytes);

            // Dynamic truncation
            int offset = hash[hash.length - 1] & 0x0F;
            int code = ((hash[offset] & 0x7F) << 24)
                     | ((hash[offset + 1] & 0xFF) << 16)
                     | ((hash[offset + 2] & 0xFF) << 8)
                     | (hash[offset + 3] & 0xFF);
            return String.format("%06d", code % 1_000_000);
        } catch (Exception e) {
            log.error("TOTP generation failed: {}", e.getMessage());
            return "000000";
        }
    }

    private byte[] longToBytes(long value) {
        byte[] result = new byte[8];
        for (int i = 7; i >= 0; i--) {
            result[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return result;
    }

    private byte[] hexDecode(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return out;
    }

    private byte[] base32Decode(String input) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        int buffer = 0, bitsLeft = 0, index = 0;
        byte[] result = new byte[input.length() * 5 / 8];
        for (char c : input.toCharArray()) {
            int val = alphabet.indexOf(c);
            if (val < 0) continue;
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                result[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        byte[] trimmed = new byte[index];
        System.arraycopy(result, 0, trimmed, 0, index);
        return trimmed;
    }
}
