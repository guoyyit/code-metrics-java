package com.github.guoyyit.codemetricsjava.model;

import java.util.List;
import java.util.Map;

public record RuleConfig(
        String version,
        Map<String, RuleSettings> rules
) {
    public record RuleSettings(
            boolean enabled,
            String severity,
            String scope,
            double threshold,
            List<String> pathPatterns,
            String reasonTemplate
    ) {
    }
}
