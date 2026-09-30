package com.github.guoyyit.codemetricsjava.service;

import com.github.guoyyit.codemetricsjava.config.MetricsProperties;
import com.github.guoyyit.codemetricsjava.engine.IncrementalPlanner;
import com.github.guoyyit.codemetricsjava.engine.JavaFileAnalyzer;
import com.github.guoyyit.codemetricsjava.engine.MemoryLimiter;
import com.github.guoyyit.codemetricsjava.engine.SourceFile;
import com.github.guoyyit.codemetricsjava.engine.SourceScanner;
import com.github.guoyyit.codemetricsjava.model.AnalysisRequest;
import com.github.guoyyit.codemetricsjava.model.AnalysisSnapshot;
import com.github.guoyyit.codemetricsjava.model.AnalysisStatus;
import com.github.guoyyit.codemetricsjava.model.FileAnalysisResult;
import com.github.guoyyit.codemetricsjava.model.IncrementalPlan;
import com.github.guoyyit.codemetricsjava.model.RuleConfig;
import com.github.guoyyit.codemetricsjava.model.SnapshotSummary;
import com.github.guoyyit.codemetricsjava.rules.RuleEvaluator;
import com.github.guoyyit.codemetricsjava.storage.JsonUtil;
import com.github.guoyyit.codemetricsjava.storage.StorageService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 分析编排：扫描、并行解析、规则判定、豁免标注、汇总与原子持久化。
 * 每次调用使用独立的线程池与内存闸，所有中间状态为方法内局部变量，并发批次互不串扰。
 */
@Service
public class AnalysisService {

    private final MetricsProperties properties;
    private final StorageService storage;
    private final SourceScanner scanner;
    private final JavaFileAnalyzer analyzer;
    private final RuleEvaluator evaluator;
    private final IncrementalPlanner planner;
    private final ExemptionService exemptionService;
    private final RuleConfigService ruleConfigService;
    private final ReentrantLock commitLock = new ReentrantLock();

    public AnalysisService(MetricsProperties properties,
                           StorageService storage,
                           SourceScanner scanner,
                           JavaFileAnalyzer analyzer,
                           RuleEvaluator evaluator,
                           IncrementalPlanner planner,
                           ExemptionService exemptionService,
                           RuleConfigService ruleConfigService) {
        this.properties = properties;
        this.storage = storage;
        this.scanner = scanner;
        this.analyzer = analyzer;
        this.evaluator = evaluator;
        this.planner = planner;
        this.exemptionService = exemptionService;
        this.ruleConfigService = ruleConfigService;
        storage.cleanupTempSnapshotFiles();
    }

    public AnalysisSnapshot analyze(AnalysisRequest request) {
        long startedAt = System.nanoTime();
        Path root = Path.of(request.rootPath == null || request.rootPath.isBlank()
                ? System.getProperty("user.dir") : request.rootPath).toAbsolutePath().normalize();
        List<String> includes = request.includes == null || request.includes.isEmpty()
                ? new ArrayList<>(properties.getDefaultIncludes()) : new ArrayList<>(request.includes);
        List<String> excludes = new ArrayList<>(properties.getDefaultExcludes());
        if (request.excludes != null) {
            excludes.addAll(request.excludes);
        }

        List<SourceFile> sourceFiles = scanner.scan(root, includes, excludes);
        List<RuleConfig> ruleConfigs = ruleConfigService.getConfigs();
        String exemptionState = JsonUtil.toJson(exemptionService.list());
        String revision = resolveRevision(request.revision, root);
        String configFingerprint = configFingerprint(ruleConfigs, exemptionState);

        AnalysisSnapshot baseline = resolveBaseline(request, configFingerprint);
        boolean incremental = request.incremental != null
                ? request.incremental
                : baseline != null;
        if (incremental && baseline != null
                && !configFingerprint.equals(baseline.summary.configFingerprint)) {
            incremental = false;
            baseline = null;
        }
        if (!incremental) {
            baseline = null;
        }
        IncrementalPlan plan = planner.plan(baseline, sourceFiles);

        int parallelism = Math.max(1, Math.min(
                request.parallelism == null ? properties.getDefaultParallelism()
                        : request.parallelism, sourceFiles.isEmpty() ? 1 : sourceFiles.size()));
        long memoryLimit = request.memoryLimitBytes == null
                ? properties.getDefaultMemoryLimitBytes() : request.memoryLimitBytes;

        Map<String, FileAnalysisResult> reusedResults = new java.util.HashMap<>();
        if (baseline != null) {
            for (FileAnalysisResult result : baseline.results) {
                reusedResults.put(result.path, result);
            }
        }

        List<FileAnalysisResult> results = runParallel(sourceFiles, plan, reusedResults,
                ruleConfigs, parallelism, memoryLimit);
        results.sort(Comparator.comparing(result -> result.path));
        exemptionService.applySuppressions(results);

        AnalysisSnapshot snapshot = new AnalysisSnapshot();
        snapshot.results = results;
        snapshot.summary = buildSummary(results, revision, request.label,
                System.nanoTime() - startedAt, incremental, plan, configFingerprint,
                deriveSnapshotId(revision, sourceFiles, configFingerprint));
        return commit(snapshot);
    }

    private AnalysisSnapshot commit(AnalysisSnapshot snapshot) {
        commitLock.lock();
        try {
            // 相同输入得到相同快照 id 与相同内容：已存在时覆盖写入为相同字节，
            // 但返回本次计算结果（保留本次的增量/基线元信息来源），保证幂等且可观测。
            storage.saveSnapshot(snapshot);
            return snapshot;
        } finally {
            commitLock.unlock();
        }
    }

    private List<FileAnalysisResult> runParallel(List<SourceFile> sourceFiles,
                                                 IncrementalPlan plan,
                                                 Map<String, FileAnalysisResult> reusedResults,
                                                 List<RuleConfig> ruleConfigs,
                                                 int parallelism,
                                                 long memoryLimit) {
        Map<String, FileAnalysisResult> computed = new ConcurrentHashMap<>();
        List<SourceFile> changed = sourceFiles.stream()
                .filter(sourceFile -> plan.changedFiles.contains(sourceFile.path))
                .toList();
        MemoryLimiter memoryLimiter = new MemoryLimiter(memoryLimit);
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, runnable -> {
            Thread thread = new Thread(runnable, "code-metrics-analyzer");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Deque<Future<?>> futures = new ArrayDeque<>();
            for (SourceFile sourceFile : changed) {
                futures.add(executor.submit(() -> {
                    try {
                        FileAnalysisResult result = memoryLimiter.runWithPermit(
                                Math.max(1L, sourceFile.size * 4L),
                                () -> analyzer.analyze(sourceFile));
                        evaluator.evaluate(result, ruleConfigs);
                        computed.put(result.path, result);
                    } catch (Throwable failure) {
                        computed.put(sourceFile.path, FileAnalysisResult.error(
                                sourceFile.path, sourceFile.sha256,
                                "ANALYSIS_FAILURE: " + failure.getClass().getSimpleName()
                                        + ": " + failure.getMessage()));
                    }
                }));
            }
            while (!futures.isEmpty()) {
                try {
                    futures.pollFirst().get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("分析被中断", e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException("分析任务失败", e.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        List<FileAnalysisResult> all = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            FileAnalysisResult result = computed.get(sourceFile.path);
            if (result == null) {
                result = reusedResults.get(sourceFile.path);
            }
            if (result != null) {
                all.add(result);
            }
        }
        return all;
    }

    private SnapshotSummary buildSummary(List<FileAnalysisResult> results, String revision,
                                         String label, long durationNanos, boolean incremental,
                                         IncrementalPlan plan, String configFingerprint,
                                         String snapshotId) {
        SnapshotSummary summary = new SnapshotSummary();
        summary.snapshotId = snapshotId;
        summary.revision = revision;
        summary.label = label;
        summary.createdAt = Instant.now().toString();
        summary.durationMillis = Duration.ofNanos(durationNanos).toMillis();
        summary.incremental = incremental;
        summary.baselineId = plan.baselineId;
        summary.configFingerprint = configFingerprint;
        summary.changedFileCount = plan.changedFiles.size();
        summary.fileCount = results.size();
        for (FileAnalysisResult result : results) {
            if (result.status == AnalysisStatus.OK) {
                summary.okCount++;
                if (result.metrics != null) {
                    summary.loc += result.metrics.loc;
                    summary.sloc += result.metrics.sloc;
                }
            } else {
                summary.parseErrorCount++;
            }
            for (var finding : result.findings) {
                summary.findingCount++;
                if (finding.suppressed) {
                    summary.suppressedCount++;
                } else {
                    summary.activeFindingCount++;
                }
            }
        }
        return summary;
    }

    private AnalysisSnapshot resolveBaseline(AnalysisRequest request, String configFingerprint) {
        if (request.baselineId != null && !request.baselineId.isBlank()) {
            AnalysisSnapshot baseline = storage.loadSnapshot(request.baselineId);
            if (baseline == null) {
                throw new IllegalArgumentException("基线快照不存在: " + request.baselineId);
            }
            return baseline;
        }
        return storage.listSnapshots().stream()
                .filter(summary -> configFingerprint.equals(summary.configFingerprint))
                .findFirst()
                .map(summary -> storage.loadSnapshot(summary.snapshotId))
                .orElse(null);
    }

    private String resolveRevision(String requested, Path root) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        try {
            Process process = new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "HEAD")
                    .redirectErrorStream(false).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .trim();
            process.waitFor();
            if (process.exitValue() == 0 && !output.isBlank()) {
                return output;
            }
        } catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return "working-tree";
    }

    private String deriveSnapshotId(String revision, List<SourceFile> sourceFiles,
                                    String configFingerprint) {
        String inventory = sourceFiles.stream()
                .map(sourceFile -> sourceFile.path + ":" + sourceFile.sha256)
                .collect(Collectors.joining("\n"));
        String basis = revision + "\n" + inventory + "\n" + configFingerprint;
        return "snap-" + sha256(basis.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    private String configFingerprint(List<RuleConfig> ruleConfigs, String exemptionState) {
        StringBuilder basis = new StringBuilder();
        ruleConfigs.stream()
                .sorted(java.util.Comparator.comparing(config -> config.ruleId))
                .forEach(config -> basis.append(config.ruleId)
                        .append('|').append(config.enabled)
                        .append('|').append(normalizedNumber(config.threshold))
                        .append('|').append(config.include == null ? "" : String.join(",", config.include))
                        .append('|').append(config.exclude == null ? "" : String.join(",", config.exclude))
                        .append(';'));
        basis.append('\n').append(exemptionState);
        return sha256(basis.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String normalizedNumber(double value) {
        return value == Math.floor(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
