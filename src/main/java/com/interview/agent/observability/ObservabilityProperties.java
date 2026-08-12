package com.interview.agent.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 可观测性配置（application.yml observability.*）。
 * 成本单价单位：元 / 千 token，按 DashScope 控制台价格配置。
 */
@Component
@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {
    private Cost cost = new Cost();
    private int retentionDays = 30;
    private int promptExcerptLength = 500;

    public Cost getCost() {
        return cost;
    }

    public void setCost(Cost cost) {
        this.cost = cost;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public int getPromptExcerptLength() {
        return promptExcerptLength;
    }

    public void setPromptExcerptLength(int promptExcerptLength) {
        this.promptExcerptLength = promptExcerptLength;
    }

    public static class Cost {
        private double inputPer1k;
        private double outputPer1k;

        public double getInputPer1k() {
            return inputPer1k;
        }

        public void setInputPer1k(double inputPer1k) {
            this.inputPer1k = inputPer1k;
        }

        public double getOutputPer1k() {
            return outputPer1k;
        }

        public void setOutputPer1k(double outputPer1k) {
            this.outputPer1k = outputPer1k;
        }
    }
}
