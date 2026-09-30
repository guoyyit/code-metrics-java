package com.github.guoyyit.codemetricsjava.analysis;

import com.github.guoyyit.codemetricsjava.model.SourceFile;
import com.github.guoyyit.codemetricsjava.rule.Fingerprints;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Discovers Java source files under a root in a deterministic order. Build
 * output directories and VCS metadata are skipped.
 */
@Component
public class SourceFileScanner {

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "target", "build", ".git", ".idea", ".gradle",
            "node_modules", "data");

    public List<SourceFile> scan(Path root) {
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("analysis root does not exist: " + root);
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !excluded(root, path))
                    .sorted()
                    .map(path -> read(root, path))
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private boolean excluded(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            if (EXCLUDED_DIRS.contains(part.getFileName().toString())) {
                return true;
            }
        }
        return false;
    }

    private SourceFile read(Path root, Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            String content = new String(bytes, StandardCharsets.UTF_8);
            String relative = root.toAbsolutePath().relativize(path.toAbsolutePath())
                    .toString().replace('\\', '/');
            return new SourceFile(relative,
                    Fingerprints.sha256(content), bytes.length, content);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
