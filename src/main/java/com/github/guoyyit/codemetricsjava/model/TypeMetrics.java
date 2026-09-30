package com.github.guoyyit.codemetricsjava.model;

import java.util.List;

public record TypeMetrics(
        String qualifiedName,
        String kind,
        SourceLocation location,
        int linesOfCode,
        int methodCount,
        int fieldCount,
        int weightedMethodComplexity,
        int fanOut,
        List<MethodMetrics> methods
) {
}
