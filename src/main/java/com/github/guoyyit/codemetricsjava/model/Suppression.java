package com.github.guoyyit.codemetricsjava.model;

import java.time.Instant;

public record Suppression(
        String id,
        String ruleId,
        String fingerprint,
        String path,
        String reason,
        String createdBy,
        Instant createdAt,
        Instant revokedAt,
        String revokedBy
) {
    public boolean activeAt(Instant instant) {
        return !createdAt.isAfter(instant)
                && (revokedAt == null || revokedAt.isAfter(instant));
    }
}
