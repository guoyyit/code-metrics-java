package com.github.guoyyit.codemetricsjava.model;

import java.util.Arrays;
import java.util.List;

/**
 * 内建坏味道规则标识及其默认阈值。具体判定口径见 docs/metrics.md。
 */
public enum SmellRuleId {

    LONG_METHOD("loc", AnalysisLevel.METHOD, 50, "方法物理行数超过阈值"),
    COMPLEX_METHOD("complexity", AnalysisLevel.METHOD, 10, "方法圈复杂度达到或超过阈值"),
    LONG_PARAMETER_LIST("parameterCount", AnalysisLevel.METHOD, 5, "方法参数数量达到或超过阈值"),
    LARGE_CLASS("loc", AnalysisLevel.TYPE, 300, "类型物理行数超过阈值"),
    GOD_CLASS("wmc", AnalysisLevel.TYPE, 40, "类型加权方法数 WMC 达到或超过阈值"),
    HIGH_COUPLING("fanOut", AnalysisLevel.TYPE, 15, "类型扇出（依赖的不同类型数）超过阈值");

    private final String metric;
    private final AnalysisLevel level;
    private final double defaultThreshold;
    private final String description;

    SmellRuleId(String metric, AnalysisLevel level, double defaultThreshold, String description) {
        this.metric = metric;
        this.level = level;
        this.defaultThreshold = defaultThreshold;
        this.description = description;
    }

    public String getMetric() {
        return metric;
    }

    public AnalysisLevel getLevel() {
        return level;
    }

    public double getDefaultThreshold() {
        return defaultThreshold;
    }

    public String getDescription() {
        return description;
    }

    public static List<SmellRuleId> all() {
        return Arrays.asList(values());
    }
}
