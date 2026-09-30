package com.github.guoyyit.codemetricsjava.persistence;

import com.github.guoyyit.codemetricsjava.config.JsonSupport;
import com.github.guoyyit.codemetricsjava.config.MetricsProperties;
import com.github.guoyyit.codemetricsjava.model.AnalysisReport;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Stores completed analysis reports by revision. Writes are staged and only
 * published atomically after the full report has been serialised; stale
 * staging directories are cleaned on startup so a crashed batch can never
 * surface as a partially visible result.
 */
@Component
public class ReportRepository {

    static final String STATUS_SUFFIX = ".status";

    private final MetricsProperties properties;

    public ReportRepository(MetricsProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        cleanupStaging();
    }

    public void save(AnalysisReport report) {
        Path reportsDir = reportsDir();
        Path staging = reportsDir.resolve(report.id() + ".staging");
        Path target = reportsDir.resolve(fileName(report.revision()));
        Path statusTarget = reportsDir.resolve(fileName(report.revision()) + STATUS_SUFFIX);
        try {
            if (Files.exists(staging)) {
                deleteRecursively(staging);
            }
            Files.createDirectories(staging);
            JsonSupport.writeAtomically(staging.resolve("report.json"), report);
            Files.writeString(staging.resolve("status"), report.status());
            Files.createDirectories(reportsDir);
            move(staging.resolve("report.json"), target);
            move(staging.resolve("status"), statusTarget);
            deleteRecursively(staging);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public Optional<AnalysisReport> findByRevision(String revision) {
        Path file = reportsDir().resolve(fileName(revision));
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return Optional.of(JsonSupport.read(file, AnalysisReport.class));
    }

    public Optional<AnalysisReport> latest() {
        return history(1).stream().findFirst();
    }

    public List<AnalysisReport> history(int limit) {
        Path directory = reportsDir();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> JsonSupport.read(path, AnalysisReport.class))
                    .sorted(Comparator.comparing(AnalysisReport::finishedAt).reversed())
                    .limit(limit <= 0 ? Integer.MAX_VALUE : limit)
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void cleanupStaging() {
        Path directory = reportsDir();
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.list(directory)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".staging"))
                    .forEach(this::deleteRecursively);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(child -> {
                try {
                    Files.deleteIfExists(child);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private String fileName(String revision) {
        String safe = revision.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe + ".json";
    }

    private Path reportsDir() {
        return Path.of(properties.getStorageDir()).resolve("reports");
    }
}
