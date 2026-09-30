package com.github.guoyyit.codemetricsjava.model;

public record SourceFile(
        String path,
        String contentHash,
        long sizeBytes,
        String content
) {
}
