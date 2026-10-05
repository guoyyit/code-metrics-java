package com.github.guoyyit.codemetricsjava.model;

import java.util.List;

public record BaselineComparison(
        String baselineRevision,
        String targetRevision,
        List<SmellFinding> added,
        List<SmellFinding> removed,
        List<MetricDelta> metricDeltas,
        SummarySnapshot baseline,
        SummarySnapshot target
) {
    public record MetricDelta(
            String metric,
            String scope,
            String qualifiedName,
            double baselineValue,
            double targetValue,
            double delta,
            String direction
    ) {
    }

    public record SummarySnapshot(
            String revision,
            java.util.Map<String, Double> values
    ) {
    }
}
