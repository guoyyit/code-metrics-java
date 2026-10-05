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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Orchestrates full and incremental analyses.
 *
 * <p>Determinism guarantees: files are scanned in sorted order, findings are
 * sorted, and all aggregate numbers are computed from the same ordered
 * inputs. The report id is a stable hash of revision id, rule-config hash
 * and source code content, so repeated runs over identical inputs produce
 * the same id and byte-identical reports apart from timestamps; run mode,
 * timing, scheduling and restarts never influence the id. Analyses with the
 * same id are serialized so concurrent duplicate submissions cannot race.
 */
@Service
public class AnalyzerService {

    private final MetricsProperties properties;
    private final SourceFileScanner scanner;
    private final FileAnalysisWorker worker;
    private final RuleConfigService ruleConfigService;
    private final IncrementalCache incrementalCache;
    private final ReportRepository reportRepository;
    private final SuppressionService suppressionService;
    private final ConcurrentHashMap<String, Object> idLocks = new ConcurrentHashMap<>();

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
        Instant startedAt = Instant.now();
        Path root = Path.of(request.rootPath() == null ? "." : request.rootPath())
                .toAbsolutePath().normalize();
        RuleConfig config = ruleConfigService.current();
        String configHash = ruleConfigService.configHash();
        String analyzerVersion = properties.getAnalyzerVersion();
        String revision = resolveRevision(root, request.revision());

        List<SourceFile> sources = scanner.scan(root);
        String reportId = deterministicReportId(revision, configHash, sources);
        Object idLock = idLocks.computeIfAbsent(reportId, key -> new Object());
        synchronized (idLock) {
            try {
                return runAnalysis(request, root, config, configHash, analyzerVersion,
                        revision, sources, reportId, startedAt);
            } finally {
                idLocks.remove(reportId, idLock);
            }
        }
    }

    private AnalysisReport runAnalysis(AnalysisRequest request, Path root,
                                       RuleConfig config, String configHash,
                                       String analyzerVersion, String revision,
                                       List<SourceFile> sources, String reportId,
                                       Instant startedAt) {
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
    }

    private String deterministicReportId(String revision, String configHash,
                                         List<SourceFile> sources) {
        String codeFingerprint = sources.stream()
                .map(source -> source.path() + ":" + source.contentHash())
                .collect(Collectors.joining("\n"));
        return "RPT-" + Fingerprints.sha256(
                "v1|" + revision + "|" + configHash + "|"
                        + Fingerprints.sha256(codeFingerprint)).substring(0, 12);
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
        long methods = files.stream()
                .mapToLong(file -> file.summary().totalMethods())
                .sum();
        long totalParameters = files.stream()
                .mapToLong(file -> file.summary().totalParameters())
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
