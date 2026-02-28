package com.jay.stagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.PropertyPlaceholderHelper;

import java.io.InputStream;
import java.util.List;

/**
 * Loads and exposes all configuration from config.yaml.
 * Values are read once at startup and cached. Edit config.yaml and restart to apply changes.
 */
@Slf4j
@Component
public class AgentConfig {

    @Value("${agent.config-file:config.yaml}")
    private String configFile;

    @Autowired
    private Environment env;

    private static final PropertyPlaceholderHelper PLACEHOLDER_HELPER =
        new PropertyPlaceholderHelper("${", "}", ":", true);

    /** Resolves ${VAR:default} placeholders using Spring Environment (env vars / system props). */
    private String resolve(String value) {
        if (value == null) return null;
        return PLACEHOLDER_HELPER.replacePlaceholders(value, key -> {
            String envVal = env.getProperty(key);
            return envVal; // null → helper falls back to the :default part
        });
    }

    // ── Sections ──────────────────────────────────────────────────────────────
    private Portfolio portfolio = new Portfolio();
    private PositionSizing positionSizing = new PositionSizing();
    private Risk risk = new Risk();
    private Signal signal = new Signal();
    private ConfidenceWeights confidenceWeights = new ConfidenceWeights();
    private Filters filters = new Filters();
    private Fundamental fundamental = new Fundamental();
    private Technical technical = new Technical();
    private Macro macro = new Macro();
    private Execution execution = new Execution();
    private Broker broker = new Broker();
    private Telegram telegram = new Telegram();
    private PaperTrading paperTrading = new PaperTrading();
    private List<String> watchlist = List.of();

    @PostConstruct
    public void load() {
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            mapper.setPropertyNamingStrategy(
                com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
            mapper.configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            InputStream is = getClass().getClassLoader().getResourceAsStream(configFile);
            if (is == null) {
                log.warn("Config file '{}' not found on classpath — using defaults", configFile);
                return;
            }
            ConfigRoot root = mapper.readValue(is, ConfigRoot.class);
            this.portfolio       = root.getPortfolio();
            this.positionSizing  = root.getPositionSizing();
            this.risk            = root.getRisk();
            this.signal          = root.getSignal();
            this.confidenceWeights = root.getConfidenceWeights();
            this.filters         = root.getFilters();
            this.fundamental     = root.getFundamental();
            this.technical       = root.getTechnical();
            this.macro           = root.getMacro();
            this.execution       = root.getExecution();
            this.broker          = root.getBroker();
            this.telegram        = root.getTelegram();
            this.paperTrading    = root.getPaperTrading();

            // Resolve ${VAR:default} placeholders that Jackson reads as literal strings
            this.broker.setApiKey(resolve(this.broker.getApiKey()));
            this.broker.setClientId(resolve(this.broker.getClientId()));
            this.broker.setMpin(resolve(this.broker.getMpin()));
            this.broker.setTotpSecret(resolve(this.broker.getTotpSecret()));
            this.telegram.setBotToken(resolve(this.telegram.getBotToken()));
            this.telegram.setChatId(resolve(this.telegram.getChatId()));
            this.watchlist       = root.getWatchlist() != null ? root.getWatchlist() : List.of();
            log.info("AgentConfig loaded from '{}'. Paper-trading mode: {}", configFile, paperTrading.isEnabled());
        } catch (Exception e) {
            log.error("Failed to load config.yaml — agent will use defaults: {}", e.getMessage());
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────
    public Portfolio portfolio()           { return portfolio; }
    public PositionSizing positionSizing() { return positionSizing; }
    public Risk risk()                     { return risk; }
    public Signal signal()                 { return signal; }
    public ConfidenceWeights weights()     { return confidenceWeights; }
    public Filters filters()               { return filters; }
    public Fundamental fundamental()       { return fundamental; }
    public Technical technical()           { return technical; }
    public Macro macro()                   { return macro; }
    public Execution execution()           { return execution; }
    public Broker broker()                 { return broker; }
    public Telegram telegram()             { return telegram; }
    public PaperTrading paperTrading()     { return paperTrading; }
    public List<String> watchlist()        { return watchlist; }

    // ── Config POJOs ──────────────────────────────────────────────────────────

    @Data public static class ConfigRoot {
        private Portfolio portfolio = new Portfolio();
        private PositionSizing positionSizing = new PositionSizing();
        private Risk risk = new Risk();
        private Signal signal = new Signal();
        private ConfidenceWeights confidenceWeights = new ConfidenceWeights();
        private Filters filters = new Filters();
        private Fundamental fundamental = new Fundamental();
        private Technical technical = new Technical();
        private Macro macro = new Macro();
        private Execution execution = new Execution();
        private Broker broker = new Broker();
        private Telegram telegram = new Telegram();
        private PaperTrading paperTrading = new PaperTrading();
        private List<String> watchlist;
    }

    @Data public static class Portfolio {
        private double totalValueInr = 500000;
        private double emergencyCashBufferPct = 20;
        private int maxOpenPositions = 15;
        private double mutualFundAllocationPct = 40;
    }

    @Data public static class PositionSizing {
        private double maxSingleStockPct = 10;
        private double maxSectorPct = 25;
        private double minPositionSizeInr = 5000;
        private double hardCapSingleStockPct = 25;
    }

    @Data public static class Risk {
        private double maxSingleTradeDrawdownPct = 15;
        private double maxPortfolioDrawdownPct = 20;
        private double minStopLossPct = 3;
        private double maxStopLossPct = 15;
        private double minRiskRewardRatio = 2.0;
        private double trailingStopActivatePct = 10;
        private int earningsBlackoutDays = 5;
        private int maxNewBuysPerWeek = 3;
    }

    @Data public static class Signal {
        private double minConfidenceToNotify = 60;
        private double autoExecuteThreshold = 90;
        private int approvalWindowMinutes = 30;
    }

    @Data public static class ConfidenceWeights {
        private double fundamental = 0.35;
        private double technical = 0.30;
        private double macro = 0.20;
        private double riskReward = 0.15;
    }

    @Data public static class Filters {
        private double minMarketCapCr = 500;
        private double minAvgDailyVolumeCr = 1.0;
        private double minStockPriceInr = 10;
        private boolean includeBse = false;
        private int maxAnalysisUniverse = 500;
    }

    @Data public static class Fundamental {
        private double minRevenueCagr3yPct = 10;
        private double minRoePct = 15;
        private double minRocePct = 12;
        private double maxDebtToEquity = 1.0;
        private double hardMaxDebtToEquity = 2.0;
        private double minPromoterHoldingPct = 40;
        private double maxPegRatio = 1.5;
    }

    @Data public static class Technical {
        private int dmaLong = 200;
        private int dmaMedium = 50;
        private int dmaShort = 20;
        private int rsiPeriod = 14;
        private double rsiOverbought = 75;
        private double rsiOversold = 40;
        private double maxPctAbove200dma = 15;
    }

    @Data public static class Macro {
        private double vixNoBuysThreshold = 25;
        private double vixCautionThreshold = 20;
        private double vixFavorableThreshold = 15;
        private int fiiSellingDaysThreshold = 10;
    }

    @Data public static class Execution {
        private boolean autoMode = false;
        private String orderType = "LIMIT";
        private boolean allowMargin = false;
        private int orderFillTimeoutMinutes = 30;
    }

    @Data public static class Broker {
        private String name = "ANGEL_ONE";
        private String apiKey = "";
        private String clientId = "";
        private String mpin = "";
        private String totpSecret = "";
    }

    @Data public static class Telegram {
        private String botToken = "";
        private String chatId = "";
        private int pollingIntervalSeconds = 2;
    }

    @Data public static class PaperTrading {
        private boolean enabled = true;
        private double virtualBalanceInr = 500000;
    }
}