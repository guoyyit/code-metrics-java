package com.github.guoyyit.codemetricsjava.persistence;

import com.github.guoyyit.codemetricsjava.config.JsonSupport;
import com.github.guoyyit.codemetricsjava.config.MetricsProperties;
import com.github.guoyyit.codemetricsjava.model.FileMetrics;
import com.github.guoyyit.codemetricsjava.rule.Fingerprints;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * File-level incremental cache. A cached entry is reused only when all of
 * content hash, rule-config hash and analyzer version match. Since rules
 * never mutate {@link FileMetrics}, reusing a cached metrics object across
 * analyses cannot cause cross-batch contamination.
 */
@Component
public class IncrementalCache {

    private final MetricsProperties properties;

    public IncrementalCache(MetricsProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(cacheDir());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public Optional<FileMetrics> lookup(String path, String contentHash,
                                        String configHash, String analyzerVersion) {
        Path file = entryFile(path);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        CachedEntry entry = JsonSupport.read(file, CachedEntry.class);
        if (entry.matches(path, contentHash, configHash, analyzerVersion)) {
            return Optional.of(entry.metrics());
        }
        return Optional.empty();
    }

    public void put(FileMetrics metrics, String configHash, String analyzerVersion) {
        CachedEntry entry = new CachedEntry(metrics.path(), metrics.contentHash(),
                configHash, analyzerVersion, metrics);
        JsonSupport.writeAtomically(entryFile(metrics.path()), entry);
    }

    public void remove(String path) {
        try {
            Files.deleteIfExists(entryFile(path));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Path entryFile(String path) {
        String safe = path.replaceAll("[^A-Za-z0-9._-]", "_");
        return cacheDir().resolve(Fingerprints.sha256(path).substring(0, 16)
                + "-" + safe + ".json");
    }

    private Path cacheDir() {
        return Path.of(properties.getStorageDir()).resolve("incremental");
    }

    private record CachedEntry(
            String path,
            String contentHash,
            String configHash,
            String analyzerVersion,
            FileMetrics metrics
    ) {
        boolean matches(String otherPath, String otherHash,
                        String otherConfigHash, String otherAnalyzerVersion) {
            return path.equals(otherPath)
                    && contentHash.equals(otherHash)
                    && configHash.equals(otherConfigHash)
                    && analyzerVersion.equals(otherAnalyzerVersion);
        }
    }
}
