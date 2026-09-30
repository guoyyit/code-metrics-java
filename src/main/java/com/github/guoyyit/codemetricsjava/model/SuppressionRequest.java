package com.github.guoyyit.codemetricsjava.model;

public record SuppressionRequest(
        String ruleId,
        String fingerprint,
        String path,
        String reason,
        String createdBy
) {
}
