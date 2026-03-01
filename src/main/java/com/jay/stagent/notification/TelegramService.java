package com.jay.stagent.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jay.stagent.config.AgentConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Telegram Bot API client.
 * Sends messages via sendMessage and polls for replies via getUpdates.
 * All interaction uses OkHttp — no Telegram SDK dependency.
 *
 * Bot setup: Create a bot via @BotFather on Telegram, get the token.
 * Get chat ID: Send a message to your bot, then call getUpdates to find your chat_id.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramService {

    private static final String API_BASE = "https://api.telegram.org/bot";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    // File used to persist the Telegram update offset across restarts.
    // Prevents re-processing old APPROVE/REJECT messages after a service restart.
    private static final Path OFFSET_FILE =
        Path.of(System.getProperty("user.home"), ".stock-agent-telegram-offset");

    private final AgentConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private OkHttpClient httpClient;
    private long lastUpdateId = 0;

    // Registered message handlers
    private final List<Consumer<TelegramMessage>> messageHandlers = new ArrayList<>();

    public record TelegramMessage(long chatId, long messageId, String text, String username) {}

    @PostConstruct
    public void init() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS) // Slightly above Telegram's 30s long-poll
            .build();
        // Restore the update offset saved by the previous run so we don't replay
        // APPROVE/REJECT messages that were already processed before the restart.
        try {
            if (Files.exists(OFFSET_FILE)) {
                lastUpdateId = Long.parseLong(Files.readString(OFFSET_FILE).trim());
                log.info("TelegramService: restored update offset {} from disk", lastUpdateId);
            }
        } catch (Exception e) {
            log.warn("TelegramService: could not read offset file ({}), starting from 0", e.getMessage());
        }
        log.info("TelegramService initialized. Bot configured: {}",
            !config.telegram().getBotToken().isBlank() &&
            !config.telegram().getBotToken().equals("YOUR_BOT_TOKEN"));
    }

    // ── Sending Messages ───────────────────────────────────────────────────────

    /**
     * Sends a message to the configured chat ID.
     * Uses Markdown V2 parse mode for formatting.
     */
    public boolean sendMessage(String text) {
        return sendMessageTo(config.telegram().getChatId(), text);
    }

    public boolean sendMessageTo(String chatId, String text) {
        if (chatId == null || chatId.isBlank() || chatId.equals("YOUR_CHAT_ID")) {
            log.warn("Telegram chat ID not configured — message not sent");
            return false;
        }

        String token = config.telegram().getBotToken();
        if (token == null || token.isBlank() || token.equals("YOUR_BOT_TOKEN")) {
            log.warn("Telegram bot token not configured — message not sent");
            return false;
        }

        try {
            // Build JSON payload
            String payload = mapper.createObjectNode()
                .put("chat_id", chatId)
                .put("text", text)
                .put("parse_mode", "HTML")
                .toString();

            String url = API_BASE + token + "/sendMessage";
            Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(payload, JSON))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    log.debug("Telegram message sent successfully");
                    return true;
                } else {
                    log.error("Telegram sendMessage failed: {} — {}",
                        response.code(), response.body() != null ? response.body().string() : "");
                    return false;
                }
            }
        } catch (IOException e) {
            log.error("Telegram sendMessage exception: {}", e.getMessage());
            return false;
        }
    }

    // ── Receiving Messages (Long-Polling) ──────────────────────────────────────

    /**
     * Registers a handler for incoming Telegram messages.
     * Called by ApprovalGateway to handle APPROVE/REJECT replies.
     */
    public void addMessageHandler(Consumer<TelegramMessage> handler) {
        messageHandlers.add(handler);
    }

    /**
     * Polls Telegram for new messages (long-polling, 30s timeout).
     * Called by TradingScheduler on a fixed interval.
     */
    public void pollForMessages() {
        String token = config.telegram().getBotToken();
        if (token == null || token.isBlank() || token.equals("YOUR_BOT_TOKEN")) return;

        try {
            String url = API_BASE + token + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=2";
            Request request = new Request.Builder().url(url).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return;

                JsonNode root = mapper.readTree(response.body().string());
                if (!root.path("ok").asBoolean()) return;

                JsonNode updates = root.path("result");
                long highestId = lastUpdateId;
                for (JsonNode update : updates) {
                    long updateId = update.path("update_id").asLong();
                    if (updateId > highestId) highestId = updateId;

                    JsonNode msg = update.path("message");
                    if (msg.isMissingNode()) continue;

                    long chatId    = msg.path("chat").path("id").asLong();
                    long messageId = msg.path("message_id").asLong();
                    String text    = msg.path("text").asText("").trim();
                    String username = msg.path("from").path("username").asText("");

                    if (!text.isBlank()) {
                        TelegramMessage telegramMsg = new TelegramMessage(chatId, messageId, text, username);
                        messageHandlers.forEach(h -> {
                            try { h.accept(telegramMsg); }
                            catch (Exception e) { log.error("Message handler error: {}", e.getMessage()); }
                        });
                    }
                }
                // Persist the highest seen update_id so restarts don't replay old messages
                if (highestId > lastUpdateId) {
                    lastUpdateId = highestId;
                    try {
                        Files.writeString(OFFSET_FILE, String.valueOf(lastUpdateId));
                    } catch (Exception e) {
                        log.warn("TelegramService: could not persist offset: {}", e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Telegram poll error (may be normal): {}", e.getMessage());
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /** Sends a daily summary or alert notification */
    public void sendAlert(String title, String body) {
        sendMessage(String.format("<b>%s</b>%n%s", title, body));
    }

    /** Tests if the Telegram bot is reachable and configured */
    public boolean testConnection() {
        String token = config.telegram().getBotToken();
        if (token == null || token.isBlank() || token.equals("YOUR_BOT_TOKEN")) return false;
        try {
            String url = API_BASE + token + "/getMe";
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (IOException e) {
            return false;
        }
    }
}
