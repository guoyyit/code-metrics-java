package com.github.guoyyit.codemetricsjava.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AnalysisReport(
        String id,
        String revision,
        String analyzerVersion,
        String configHash,
        String rootPath,
        boolean incremental,
        Instant startedAt,
        Instant finishedAt,
        String status,
        List<FileMetrics> files,
        List<SmellFinding> findings,
        List<FileFailure> failures,
        Map<String, Double> summary
) {
}
