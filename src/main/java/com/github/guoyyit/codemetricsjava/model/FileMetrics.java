package com.github.guoyyit.codemetricsjava.model;

import java.util.List;

public record FileMetrics(
        String path,
        String contentHash,
        String packageName,
        List<TypeMetrics> types,
        Summary summary
) {
    public record Summary(
            int totalLines,
            int sourceLinesOfCode,
            int totalMethods,
            int totalComplexity,
            int maxComplexity,
            int totalParameters,
            int totalFanOut
    ) {
    }
}
