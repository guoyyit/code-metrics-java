package com.github.guoyyit.codemetricsjava.model;

import com.github.guoyyit.codemetricsjava.rule.Fingerprints;
import com.fasterxml.jackson.annotation.JsonGetter;

public record SmellFinding(
        String ruleId,
        String severity,
        String path,
        SourceLocation location,
        String metric,
        double value,
        double threshold,
        String reason,
        boolean suppressed,
        String suppressionId
) {
    @JsonGetter("fingerprint")
    public String fingerprint() {
        String type = location == null || location.typeName() == null
                ? "" : location.typeName();
        String method = location == null || location.methodSignature() == null
                ? "" : location.methodSignature();
        return Fingerprints.sha256(ruleId + "|" + path + "|" + type + "#" + method);
    }
}
