package com.github.guoyyit.codemetricsjava.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.guoyyit.codemetricsjava.config.MetricsProperties;
import com.github.guoyyit.codemetricsjava.model.AnalysisSnapshot;
import com.github.guoyyit.codemetricsjava.model.Exemption;
import com.github.guoyyit.codemetricsjava.model.ExemptionEvent;
import com.github.guoyyit.codemetricsjava.model.RuleConfig;
import com.github.guoyyit.codemetricsjava.model.SnapshotSummary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 文件系统持久化：快照（临时文件 + 原子改名）、规则、豁免与审计日志。
 */
@Component
public class StorageService {

    private final Path storageDir;
    private final Path snapshotsDir;
    private final Path rulesFile;
    private final Path exemptionsFile;
    private final Path eventsFile;

    public StorageService(MetricsProperties properties) {
        this.storageDir = Path.of(properties.getStorageDir()).toAbsolutePath().normalize();
        this.snapshotsDir = storageDir.resolve("snapshots");
        this.rulesFile = storageDir.resolve("rules.json");
        this.exemptionsFile = storageDir.resolve("exemptions.json");
        this.eventsFile = storageDir.resolve("exemption-events.log");
        ensureLayout();
    }

    private void ensureLayout() {
        try {
            Files.createDirectories(snapshotsDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path getStorageDir() {
        return storageDir;
    }

    /**
     * 删除上次运行崩溃遗留的快照临时文件。已完成快照以原子改名提交，因此不会产生部分结果。
     */
    public void cleanupTempSnapshotFiles() {
        try (Stream<Path> paths = Files.list(snapshotsDir)) {
            paths.filter(path -> path.getFileName().toString().contains(".tmp-"))
                    .forEach(this::deleteQuietly);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void saveSnapshot(AnalysisSnapshot snapshot) {
        String snapshotId = snapshot.summary.snapshotId;
        Path target = snapshotPath(snapshotId);
        Path tempFile = snapshotsDir.resolve(snapshotId + ".json.tmp-" + Thread.currentThread().getId());
        try {
            Files.writeString(tempFile, JsonUtil.toJson(snapshot), StandardCharsets.UTF_8);
            Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(tempFile);
            throw new UncheckedIOException(e);
        }
    }

    public AnalysisSnapshot loadSnapshot(String id) {
        Path target = snapshotPath(id);
        if (!Files.exists(target)) {
            return null;
        }
        try {
            return JsonUtil.fromJson(Files.readString(target, StandardCharsets.UTF_8),
                    AnalysisSnapshot.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean snapshotExists(String snapshotId) {
        return Files.exists(snapshotPath(snapshotId));
    }

    public List<SnapshotSummary> listSnapshots() {
        List<SnapshotSummary> summaries = new ArrayList<>();
        try (Stream<Path> paths = Files.list(snapshotsDir)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            AnalysisSnapshot snapshot = JsonUtil.fromJson(
                                    Files.readString(path, StandardCharsets.UTF_8),
                                    AnalysisSnapshot.class);
                            summaries.add(snapshot.summary);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        summaries.sort(Comparator
                .comparing((SnapshotSummary summary) -> summary.createdAt)
                .reversed()
                .thenComparing(summary -> summary.snapshotId));
        return summaries;
    }

    public List<RuleConfig> loadRules() {
        if (!Files.exists(rulesFile)) {
            return new ArrayList<>();
        }
        return readJson(rulesFile, new TypeReference<List<RuleConfig>>() {
        });
    }

    public void saveRules(List<RuleConfig> configs) {
        writeJsonAtomic(rulesFile, configs);
    }

    public List<Exemption> loadExemptions() {
        if (!Files.exists(exemptionsFile)) {
            return new ArrayList<>();
        }
        return readJson(exemptionsFile, new TypeReference<List<Exemption>>() {
        });
    }

    public void saveExemptions(List<Exemption> exemptions) {
        writeJsonAtomic(exemptionsFile, exemptions);
    }

    public List<ExemptionEvent> loadExemptionEvents() {
        if (!Files.exists(eventsFile)) {
            return new ArrayList<>();
        }
        return readJson(eventsFile, new TypeReference<List<ExemptionEvent>>() {
        });
    }

    public void appendExemptionEvent(ExemptionEvent event) {
        List<ExemptionEvent> events = loadExemptionEvents();
        events.add(event);
        writeJsonAtomic(eventsFile, events);
    }

    private Path snapshotPath(String snapshotId) {
        return snapshotsDir.resolve(snapshotId + ".json");
    }

    private <T> T readJson(Path path, TypeReference<T> type) {
        try {
            return JsonUtil.fromJson(Files.readString(path, StandardCharsets.UTF_8), type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeJsonAtomic(Path target, Object value) {
        Path tempFile = target.resolveSibling(target.getFileName() + ".tmp-"
                + Thread.currentThread().getId());
        try {
            Files.writeString(tempFile, JsonUtil.toJson(value), StandardCharsets.UTF_8);
            Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailure) {
            try {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailure) {
                deleteQuietly(tempFile);
                throw new UncheckedIOException(fallbackFailure);
            }
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件清理失败不影响主流程
        }
    }

}
