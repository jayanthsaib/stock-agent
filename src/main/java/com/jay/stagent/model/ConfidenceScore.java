package com.jay.stagent.model;

import com.jay.stagent.config.AgentConfig;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ConfidenceScore {

    // Sub-scores from each analysis module (0 – 100)
    private double fundamentalScore;
    private double technicalScore;
    private double macroScore;
    private double riskRewardScore;

    // Weighted composite (0 – 100), computed by calculate()
    private double composite;

    // Reason text from each module (for report generation)
    private String fundamentalReason;
    private String technicalReason;
    private String macroReason;
    private String riskRewardReason;

    /**
     * Computes the weighted composite score and stores it.
     * Call this after setting all sub-scores.
     */
    public void calculate(AgentConfig.ConfidenceWeights weights) {
        this.composite =
            (fundamentalScore * weights.getFundamental()) +
            (technicalScore   * weights.getTechnical())   +
            (macroScore       * weights.getMacro())        +
            (riskRewardScore  * weights.getRiskReward());
    }

    /** Human-readable classification of the composite score */
    public String classification() {
        if (composite >= 85) return "HIGH CONVICTION";
        if (composite >= 70) return "STRONG SIGNAL";
        if (composite >= 60) return "MODERATE SIGNAL";
        if (composite >= 40) return "WEAK SIGNAL";
        return "REJECT";
    }

    public String breakdownString() {
        return String.format("F:%.0f%% T:%.0f%% M:%.0f%% RR:%.0f%%",
            fundamentalScore, technicalScore, macroScore, riskRewardScore);
    }
}
