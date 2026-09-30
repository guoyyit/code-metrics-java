package com.github.guoyyit.codemetricsjava.model;

public record SourceLocation(
        String path,
        String typeName,
        String methodSignature,
        int startLine,
        int endLine
) {
}
