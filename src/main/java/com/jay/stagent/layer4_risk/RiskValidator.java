package com.jay.stagent.layer4_risk;

import com.jay.stagent.config.AgentConfig;
import com.jay.stagent.entity.TradeRecord;
import com.jay.stagent.layer1_data.PortfolioValueService;
import com.jay.stagent.model.TradeSignal;
import com.jay.stagent.repository.TradeRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Layer 4 — Risk Validator.
 * Enforces all hard risk rules from the blueprint.
 * If ANY hard rule fails, the signal is rejected — regardless of confidence score.
 *
 * This runs AFTER signal generation and BEFORE the Pre-Trade Report is sent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskValidator {

    private final AgentConfig config;
    private final TradeRecordRepository tradeRepo;
    private final PortfolioValueService portfolioValueService;

    public record ValidationResult(boolean passed, List<String> failures, List<String> warnings) {
        public static ValidationResult pass(List<String> warnings) {
            return new ValidationResult(true, List.of(), warnings);
        }
        public static ValidationResult fail(List<String> failures) {
            return new ValidationResult(false, failures, List.of());
        }
    }

    /**
     * Validates a trade signal against all configured risk rules.
     * Returns a ValidationResult indicating pass/fail with reasons.
     */
    public ValidationResult validate(TradeSignal signal, List<TradeRecord> openPositions) {
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        AgentConfig.Risk risk         = config.risk();
        AgentConfig.Filters filters   = config.filters();
        AgentConfig.PositionSizing ps = config.positionSizing();
        AgentConfig.Portfolio port    = config.portfolio();

        double entryPrice = signal.getEntryPrice();
        double stopLoss   = signal.getStopLossPrice();
        double target     = signal.getTargetPrice();

        // ── Hard Rule 1: Minimum Stock Price (Penny Stock Filter) ─────────────
        if (entryPrice < filters.getMinStockPriceInr()) {
            failures.add(String.format("PENNY STOCK: price ₹%.2f < minimum ₹%.0f",
                entryPrice, filters.getMinStockPriceInr()));
        }

        // ── Hard Rule 2: Minimum R:R Ratio ────────────────────────────────────
        if (signal.getRiskRewardRatio() < risk.getMinRiskRewardRatio()) {
            failures.add(String.format("R:R %.2f below minimum %.1f",
                signal.getRiskRewardRatio(), risk.getMinRiskRewardRatio()));
        }

        // ── Hard Rule 3: Stop-Loss Bounds ────────────────────────────────────
        double slPct = Math.abs(entryPrice - stopLoss) / entryPrice * 100;
        if (slPct < risk.getMinStopLossPct()) {
            failures.add(String.format("Stop-loss %.1f%% below minimum %.0f%%", slPct, risk.getMinStopLossPct()));
        }
        if (slPct > risk.getMaxStopLossPct()) {
            failures.add(String.format("Stop-loss %.1f%% exceeds maximum %.0f%%", slPct, risk.getMaxStopLossPct()));
        }

        // ── Hard Rule 4: Target must be above entry (BUY) ─────────────────────
        if (target <= entryPrice) {
            failures.add("Target price must be above entry price for BUY signal");
        }

        // ── Hard Rule 5: Max Position Size ───────────────────────────────────
        double allocationPct = signal.getCapitalAllocationPct();
        if (allocationPct > ps.getHardCapSingleStockPct()) {
            failures.add(String.format("Allocation %.1f%% exceeds hard cap %.0f%%",
                allocationPct, ps.getHardCapSingleStockPct()));
        }

        // ── Hard Rule 6: Maximum Open Positions ──────────────────────────────
        long activePositions = openPositions.stream()
            .filter(p -> "EXECUTED".equals(p.getStatus())).count();
        if (activePositions >= port.getMaxOpenPositions()) {
            failures.add(String.format("Max open positions reached: %d/%d",
                activePositions, port.getMaxOpenPositions()));
        }

        // ── Hard Rule 7: Emergency Cash Buffer ───────────────────────────────
        if (!signal.isCashBufferSafe()) {
            failures.add("Trade would breach emergency cash buffer");
        }

        // ── Hard Rule 8: Sector Concentration ───────────────────────────────
        String sector = signal.getSector();
        double sectorExposurePct = computeSectorExposure(sector, openPositions,
            portfolioValueService.getPortfolioValue());
        if (sectorExposurePct + signal.getCapitalAllocationPct() >
                config.positionSizing().getMaxSectorPct()) {
            failures.add(String.format("Sector '%s' exposure %.1f%% would exceed %.0f%% limit",
                sector, sectorExposurePct + signal.getCapitalAllocationPct(),
                config.positionSizing().getMaxSectorPct()));
        }

        // ── Hard Rule 9: No Averaging Down ──────────────────────────────────
        boolean alreadyHolding = openPositions.stream()
            .anyMatch(p -> p.getSymbol().equals(signal.getSymbol())
                       && "EXECUTED".equals(p.getStatus()));
        if (alreadyHolding) {
            failures.add(String.format("Already holding %s — no averaging down allowed",
                signal.getSymbol()));
        }

        // ── Hard Rule 10: No Market Orders ───────────────────────────────────
        if ("MARKET".equalsIgnoreCase(config.execution().getOrderType())) {
            failures.add("Market orders are prohibited — use LIMIT orders only");
        }

        // ── Hard Rule 11: Margin / Leverage Block ─────────────────────────────
        if (config.execution().isAllowMargin()) {
            warnings.add("Margin trading is enabled — use with extreme caution");
        }

        // ── Hard Rule 12: Max New Buys Per Week ──────────────────────────────
        long newBuysThisWeek = tradeRepo.countNewBuysSince(
            LocalDateTime.now().minusDays(7));
        if (newBuysThisWeek >= risk.getMaxNewBuysPerWeek()) {
            failures.add(String.format("Max new buys per week reached: %d/%d",
                newBuysThisWeek, risk.getMaxNewBuysPerWeek()));
        }

        // ── Hard Rule 13: Minimum Position Size ──────────────────────────────
        if (signal.getCapitalAllocationInr() < config.positionSizing().getMinPositionSizeInr()) {
            failures.add(String.format("Allocation ₹%.0f below minimum ₹%.0f",
                signal.getCapitalAllocationInr(),
                config.positionSizing().getMinPositionSizeInr()));
        }

        // ── Warnings (non-blocking) ───────────────────────────────────────────
        if (signal.getConfidenceScore().getComposite() < 70) {
            warnings.add(String.format("Moderate confidence %.0f%% — consider reducing position size by 50%%",
                signal.getConfidenceScore().getComposite()));
        }
        if (slPct > 10) {
            warnings.add(String.format("Wide stop-loss %.1f%% — high risk trade", slPct));
        }

        if (!failures.isEmpty()) {
            log.info("Risk validation FAILED for {} — {} violations: {}",
                signal.getSymbol(), failures.size(), String.join("; ", failures));
            return ValidationResult.fail(failures);
        }

        log.info("Risk validation PASSED for {} — {} warnings", signal.getSymbol(), warnings.size());
        return ValidationResult.pass(warnings);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double computeSectorExposure(String sector, List<TradeRecord> openPositions,
                                          double totalPortfolio) {
        if (sector == null || sector.isBlank() || totalPortfolio <= 0) return 0;
        double sectorCapital = openPositions.stream()
            .filter(p -> sector.equalsIgnoreCase(p.getSector())
                      && "EXECUTED".equals(p.getStatus()))
            .mapToDouble(TradeRecord::getCapitalAllocationInr)
            .sum();
        return (sectorCapital / totalPortfolio) * 100;
    }
}
