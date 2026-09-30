package com.github.guoyyit.codemetricsjava.model;

public record AnalysisRequest(
        String rootPath,
        String revision,
        boolean incremental
) {
}
