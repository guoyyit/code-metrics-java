package com.github.guoyyit.codemetricsjava.model;

public record MethodMetrics(
        String id,
        String name,
        SourceLocation location,
        int linesOfCode,
        int statements,
        int cyclomaticComplexity,
        int maxNestingDepth,
        int parameterCount
) {
}
