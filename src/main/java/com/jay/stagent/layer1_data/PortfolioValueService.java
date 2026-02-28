package com.jay.stagent.layer1_data;

import com.fasterxml.jackson.databind.JsonNode;
import com.jay.stagent.config.AgentConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Provides the current total portfolio value for position-sizing calculations.
 *
 * Paper mode  → returns config.paperTrading.virtualBalanceInr
 * Live mode   → fetches Angel One wallet:
 *                 available cash  (getRMS → availablecash)
 *               + holdings value  (getHolding → sum of quantity × ltp)
 *
 * Value is refreshed once per cycle (called from DataIngestionEngine.refreshAll).
 * Falls back to config.portfolio.totalValueInr if the API call fails.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioValueService {

    private final AngelOneClient angelOneClient;
    private final AgentConfig config;

    private volatile double cachedValue = 0;

    /**
     * Returns the current portfolio value. Uses the cached value from the last
     * refresh() call — always call refresh() at the start of each cycle.
     */
    public double getPortfolioValue() {
        if (config.paperTrading().isEnabled()) {
            return config.paperTrading().getVirtualBalanceInr();
        }
        return cachedValue > 0 ? cachedValue : config.portfolio().getTotalValueInr();
    }

    /**
     * Fetches the latest portfolio value from Angel One and caches it.
     * Called once per cycle by DataIngestionEngine.refreshAll().
     */
    public double refresh() {
        if (config.paperTrading().isEnabled()) {
            double virtual = config.paperTrading().getVirtualBalanceInr();
            log.info("Portfolio value (paper mode): ₹{}", String.format("%.2f", virtual));
            return virtual;
        }

        try {
            double cash = angelOneClient.getAvailableCash();
            double holdingsValue = computeHoldingsValue();
            cachedValue = cash + holdingsValue;
            log.info("Portfolio value refreshed — cash: ₹{}, holdings: ₹{}, total: ₹{}",
                String.format("%.2f", cash),
                String.format("%.2f", holdingsValue),
                String.format("%.2f", cachedValue));
            return cachedValue;
        } catch (Exception e) {
            log.error("Failed to fetch portfolio value from Angel One: {} — using config fallback",
                e.getMessage());
            cachedValue = config.portfolio().getTotalValueInr();
            return cachedValue;
        }
    }

    private double computeHoldingsValue() {
        JsonNode holdings = angelOneClient.getHoldings();
        if (!holdings.isArray()) return 0;

        double total = 0;
        for (JsonNode holding : holdings) {
            double qty = holding.path("quantity").asDouble(0);
            double ltp = holding.path("ltp").asDouble(0);
            total += qty * ltp;
        }
        return total;
    }
}
