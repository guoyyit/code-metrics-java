package com.github.guoyyit.codemetricsjava.model;

public record CompareRequest(
        String baselineRevision,
        String targetRevision
) {
}
