package com.github.guoyyit.codemetricsjava.analysis;

import com.github.guoyyit.codemetricsjava.config.MetricsProperties;
import com.github.guoyyit.codemetricsjava.config.RuleConfigService;
import com.github.guoyyit.codemetricsjava.model.AnalysisReport;
import com.github.guoyyit.codemetricsjava.model.AnalysisRequest;
import com.github.guoyyit.codemetricsjava.model.FileFailure;
import com.github.guoyyit.codemetricsjava.model.FileMetrics;
import com.github.guoyyit.codemetricsjava.model.RuleConfig;
import com.github.guoyyit.codemetricsjava.model.SourceFile;
import com.github.guoyyit.codemetricsjava.model.SmellFinding;
import com.github.guoyyit.codemetricsjava.persistence.IncrementalCache;
import com.github.guoyyit.codemetricsjava.persistence.ReportRepository;
import com.github.guoyyit.codemetricsjava.rule.Fingerprints;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates full and incremental analyses.
 *
 * <p>Determinism guarantees: files are scanned in sorted order, findings are
 * sorted, and all aggregate numbers are computed from the same ordered
 * inputs. The report id is derived solely from revision, rule-config hash,
 * analyzer version and the relative path + content hash of every source
 * file, so it is stable across repeated, full/incremental, reordered or
 * restarted runs and never depends on wall-clock time, scheduling or
 * execution mode. Concurrent analyses carrying the same identity are
 * serialized, so they cannot interleave their atomic publishes.
 */
@Service
public class AnalyzerService {

    /**
     * Fixed set of stripes used to serialize analyses with the same
     * deterministic report id. Two submissions hash to the same stripe iff
     * their report ids match, so identical identities never interleave while
     * distinct identities usually run in parallel. Stripes are kept for the
     * process lifetime, which bounds lock memory regardless of the number of
     * distinct revisions.
     */
    private static final int LOCK_STRIPES = 256;
    private final ReentrantLock[] identityLocks = createLockStripes();

    private static ReentrantLock[] createLockStripes() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

    private final MetricsProperties properties;
    private final SourceFileScanner scanner;
    private final FileAnalysisWorker worker;
    private final RuleConfigService ruleConfigService;
    private final IncrementalCache incrementalCache;
    private final ReportRepository reportRepository;
    private final SuppressionService suppressionService;

    public AnalyzerService(MetricsProperties properties,
                           SourceFileScanner scanner,
                           FileAnalysisWorker worker,
                           RuleConfigService ruleConfigService,
                           IncrementalCache incrementalCache,
                           ReportRepository reportRepository,
                           SuppressionService suppressionService) {
        this.properties = properties;
        this.scanner = scanner;
        this.worker = worker;
        this.ruleConfigService = ruleConfigService;
        this.incrementalCache = incrementalCache;
        this.reportRepository = reportRepository;
        this.suppressionService = suppressionService;
    }

    public AnalysisReport analyze(AnalysisRequest request) {
        Path root = Path.of(request.rootPath() == null ? "." : request.rootPath())
                .toAbsolutePath().normalize();
        String revision;
        String contentFingerprint;
        RuleConfig config = ruleConfigService.current();
        String configHash = ruleConfigService.configHash();
        String analyzerVersion = properties.getAnalyzerVersion();

        // The report id must be known before any heavy work so concurrent
        // submissions of the same identity can share one lock.
        List<SourceFile> sources = scanner.scan(root);
        revision = resolveRevision(root, request.revision());
        contentFingerprint = contentFingerprint(sources);
        String reportId = reportId(revision, configHash, analyzerVersion,
                contentFingerprint);

        ReentrantLock lock = lockFor(reportId);
        lock.lock();
        try {
            Instant startedAt = Instant.now();
            List<FileMetrics> metrics = new ArrayList<>();
            List<SmellFinding> findings = new ArrayList<>();
            List<FileFailure> failures = new ArrayList<>();
            AtomicInteger reusedCount = new AtomicInteger();

            ExecutorService executor = Executors.newFixedThreadPool(
                    Math.max(1, properties.getParallelism()),
                    runnable -> {
                        Thread thread = new Thread(runnable);
                        thread.setName("file-analyzer-" + thread.getId());
                        thread.setDaemon(true);
                        return thread;
                    });
            try {
                List<Future<PerFileResult>> futures = new ArrayList<>();
                for (SourceFile source : sources) {
                    futures.add(executor.submit(() -> processOne(
                            source, config, configHash, analyzerVersion,
                            request.incremental(), reusedCount)));
                }
                for (Future<PerFileResult> future : futures) {
                    PerFileResult result = future.get();
                    if (result.metrics() != null) {
                        metrics.add(result.metrics());
                    }
                    findings.addAll(result.findings());
                    if (result.failure() != null) {
                        failures.add(result.failure());
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("analysis interrupted", ex);
            } catch (java.util.concurrent.ExecutionException ex) {
                throw new IllegalStateException("analysis failed", ex.getCause());
            } finally {
                executor.shutdownNow();
            }

            metrics.sort(Comparator.comparing(FileMetrics::path));
            findings = sortAndSuppress(findings);
            failures.sort(Comparator.comparing(FileFailure::path));

            Instant finishedAt = Instant.now();
            AnalysisReport report = new AnalysisReport(
                    reportId,
                    revision,
                    analyzerVersion,
                    configHash,
                    root.toString(),
                    request.incremental(),
                    startedAt,
                    finishedAt,
                    "COMPLETED",
                    List.copyOf(metrics),
                    findings,
                    List.copyOf(failures),
                    summarize(metrics, findings, failures, sources.size(),
                            reusedCount.get(), request.incremental()));
            reportRepository.save(report);
            return report;
        } finally {
            lock.unlock();
        }
    }

    private String contentFingerprint(List<SourceFile> sources) {
        StringBuilder builder = new StringBuilder();
        for (SourceFile source : sources) {
            builder.append(source.path()).append(':')
                    .append(source.contentHash()).append('\n');
        }
        return Fingerprints.sha256(builder.toString());
    }

    private String reportId(String revision, String configHash,
                            String analyzerVersion, String contentFingerprint) {
        return "RPT-" + Fingerprints.sha256(
                revision + "|" + configHash + "|" + analyzerVersion
                        + "|" + contentFingerprint).substring(0, 12);
    }

    private ReentrantLock lockFor(String key) {
        int index = Math.floorMod(key.hashCode(), identityLocks.length);
        return identityLocks[index];
    }

    private PerFileResult processOne(SourceFile source, RuleConfig config,
                                     String configHash, String analyzerVersion,
                                     boolean incremental, AtomicInteger reusedCount) {
        if (incremental) {
            Optional<FileMetrics> cached = incrementalCache.lookup(
                    source.path(), source.contentHash(), configHash, analyzerVersion);
            if (cached.isPresent()) {
                reusedCount.incrementAndGet();
                FileMetrics metrics = cached.get();
                List<SmellFinding> cachedFindings =
                        new ArrayList<>(worker.reevaluate(metrics, config));
                return new PerFileResult(metrics, cachedFindings, null);
            }
        }
        FileAnalysisWorker.FileResult result = worker.analyze(source, config);
        if (result.metrics() != null) {
            incrementalCache.put(result.metrics(), configHash, analyzerVersion);
        }
        return new PerFileResult(result.metrics(), result.findings(), result.failure());
    }

    private List<SmellFinding> sortAndSuppress(List<SmellFinding> raw) {
        List<SmellFinding> applied = new ArrayList<>(raw.size());
        for (SmellFinding finding : raw) {
            applied.add(suppressionService.apply(finding));
        }
        applied.sort(Comparator
                .comparing(SmellFinding::path)
                .thenComparing(finding ->
                        finding.location() == null ? 0 : finding.location().startLine())
                .thenComparing(SmellFinding::ruleId)
                .thenComparing(SmellFinding::fingerprint));
        return List.copyOf(applied);
    }

    private Map<String, Double> summarize(List<FileMetrics> files,
                                          List<SmellFinding> findings,
                                          List<FileFailure> failures,
                                          int totalFiles, int reusedFiles,
                                          boolean incremental) {
        long sloc = files.stream().mapToLong(file -> file.summary().sourceLinesOfCode()).sum();
        long totalComplexity = files.stream()
                .mapToLong(file -> file.summary().totalComplexity()).sum();
        int maxComplexity = files.stream()
                .mapToInt(file -> file.summary().maxComplexity()).max().orElse(0);
        long totalFanOut = files.stream().mapToLong(file -> file.summary().totalFanOut()).sum();
        long totalParameters = files.stream()
                .mapToLong(file -> file.summary().totalParameters()).sum();
        long methods = files.stream()
                .mapToLong(file -> file.summary().totalMethods())
                .sum();
        long activeFindings = findings.stream().filter(finding -> !finding.suppressed()).count();
        long suppressedFindings = findings.stream().filter(SmellFinding::suppressed).count();

        Map<String, Double> summary = new LinkedHashMap<>();
        summary.put("totalFiles", (double) totalFiles);
        summary.put("analyzedFiles", (double) files.size());
        summary.put("failedFiles", (double) failures.size());
        summary.put("reusedFiles", (double) reusedFiles);
        summary.put("sourceLinesOfCode", (double) sloc);
        summary.put("totalMethods", (double) methods);
        summary.put("totalParameters", (double) totalParameters);
        summary.put("totalComplexity", (double) totalComplexity);
        summary.put("maxComplexity", (double) maxComplexity);
        summary.put("totalFanOut", (double) totalFanOut);
        summary.put("activeFindings", (double) activeFindings);
        summary.put("suppressedFindings", (double) suppressedFindings);
        summary.put("incremental", incremental ? 1.0 : 0.0);
        return Map.copyOf(summary);
    }

    private String resolveRevision(Path root, String provided) {
        if (provided != null && !provided.isBlank()) {
            return provided.trim();
        }
        return new GitRevisionDetector().detect(root).orElse("WORKING");
    }

    private record PerFileResult(FileMetrics metrics, List<SmellFinding> findings,
                                 FileFailure failure) {
    }
}
