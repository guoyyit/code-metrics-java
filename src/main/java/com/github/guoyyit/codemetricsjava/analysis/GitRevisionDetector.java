package com.github.guoyyit.codemetricsjava.analysis;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves the current git HEAD short hash for a source root. When git is
 * unavailable the caller falls back to a working-tree label.
 */
public class GitRevisionDetector {

    public Optional<String> detect(Path root) {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .directory(root.toFile())
                    .redirectErrorStream(false)
                    .start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.readLine();
            }
            int exit = process.waitFor();
            if (exit == 0 && output != null && !output.isBlank()) {
                return Optional.of(output.trim());
            }
        } catch (Exception ignored) {
            // fall back to caller default
        }
        return Optional.empty();
    }
}
