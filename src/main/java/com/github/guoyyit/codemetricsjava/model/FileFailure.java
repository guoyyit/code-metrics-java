package com.github.guoyyit.codemetricsjava.model;

public record FileFailure(
        String path,
        String reason,
        String detail
) {
}
