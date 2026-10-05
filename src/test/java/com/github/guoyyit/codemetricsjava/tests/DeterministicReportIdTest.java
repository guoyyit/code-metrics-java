package com.github.guoyyit.codemetricsjava.tests;

import com.github.guoyyit.codemetricsjava.analysis.AnalyzerService;
import com.github.guoyyit.codemetricsjava.config.MetricsProperties;
import com.github.guoyyit.codemetricsjava.config.RuleConfigService;
import com.github.guoyyit.codemetricsjava.model.AnalysisReport;
import com.github.guoyyit.codemetricsjava.model.AnalysisRequest;
import com.github.guoyyit.codemetricsjava.model.RuleConfig;
import com.github.guoyyit.codemetricsjava.persistence.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "codemetrics.parallelism=2")
class DeterministicReportIdTest {

    private static final Set<String> RUN_METADATA_KEYS = Set.of(
            "analyzedFiles", "failedFiles", "reusedFiles",
            "activeFindings", "suppressedFindings", "incremental");

    private static final String SOURCE = """
            package demo;

            import java.util.ArrayList;
            import java.util.List;

            public class Calculator {

                public int add(int left, int right) {
                    return left + right;
                }

                public void collect(List<String> items) {
                    ArrayList<String> copy = new ArrayList<>(items);
                    copy.add("extra");
                }
            }
            """;

    @TempDir
    Path tempDir;

    @Autowired
    AnalyzerService analyzerService;

    @Autowired
    ReportRepository reportRepository;

    @Autowired
    MetricsProperties properties;

    @Autowired
    RuleConfigService ruleConfigService;

    private Path storage;

    @BeforeEach
    void setUp() throws Exception {
        storage = tempDir.resolve("storage");
        Files.createDirectories(storage);
        properties.setStorageDir(storage.toString());
    }

    private Path projectWith(String code) throws Exception {
        Path project = tempDir.resolve("project-" + System.nanoTime());
        Path sourceDir = project.resolve("src/demo");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("Calculator.java"), code);
        return project;
    }

    @Test
    void repeatedRunsReturnIdenticalReportId() throws Exception {
        Path project = projectWith(SOURCE);
        AnalysisReport first = analyzerService.analyze(
                new AnalysisRequest(project.toString(), "rev-1", false));
        AnalysisReport second = analyzerService.analyze(
                new AnalysisRequest(project.toString(), "rev-1", false));

        assertEquals(first.id(), second.id(),
                "same revision, code and config must produce the same report id");
        assertBusinessEqual(first, second);
    }

    @Test
    void fullAndIncrementalRunsShareReportId() throws Exception {
        Path project = projectWith(SOURCE);
        AnalysisReport full = analyzerService.analyze(
                new AnalysisRequest(project.toString(), "rev-mode", false));
        AnalysisReport incremental = analyzerService.analyze(
                new AnalysisRequest(project.toString(), "rev-mode", true));

        assertEquals(full.id(), incremental.id(),
                "run mode must not influence the deterministic report id");
        assertBusinessEqual(full, incremental);
    }

    @Test
    void reportIdChangesWhenAnyInputChanges() throws Exception {
        Path baseline = projectWith(SOURCE);
        AnalysisReport base = analyzerService.analyze(
                new AnalysisRequest(baseline.toString(), "rev-a", false));

        AnalysisReport otherRevision = analyzerService.analyze(
                new AnalysisRequest(baseline.toString(), "rev-b", false));
        assertNotEquals(base.id(), otherRevision.id(),
                "different revision id must change report id");

        Path changedCode = projectWith(SOURCE.replace("return left + right;",
                "return left + right + 1;"));
        AnalysisReport otherCode = analyzerService.analyze(
                new AnalysisRequest(changedCode.toString(), "rev-a", false));
        assertNotEquals(base.id(), otherCode.id(),
                "different code content must change report id");

        Path movedRoot = projectWith(SOURCE);
        AnalysisReport otherRoot = analyzerService.analyze(
                new AnalysisRequest(movedRoot.toString(), "rev-a", false));
        assertEquals(base.id(), otherRoot.id(),
                "the root path is run metadata and must not change report id");
    }

    @Test
    void reportIdChangesWhenRuleConfigChanges() throws Exception {
        Path project = projectWith(SOURCE);
        AnalysisReport base = analyzerService.analyze(
                new AnalysisRequest(project.toString(), "rev-cfg", false));

        RuleConfig current = ruleConfigService.current();
        var settings = new java.util.LinkedHashMap<>(current.rules());
        RuleConfig.RuleSettings longMethod = settings.get("LONG_METHOD");
        settings.put("LONG_METHOD", new RuleConfig.RuleSettings(
                longMethod.enabled(), longMethod.severity(), longMethod.scope(),
                longMethod.threshold() + 5,
                longMethod.pathPatterns(), longMethod.reasonTemplate()));
        ruleConfigService.update(new RuleConfig("2", settings));

        AnalysisReport changed = analyzerService.analyze(
                new AnalysisRequest(project.toString(), "rev-cfg", false));
        assertNotEquals(base.id(), changed.id(),
                "a changed rule config must change report id even for same code");
        assertNotEquals(base.configHash(), changed.configHash());
    }

    @Test
    void duplicateSubmissionsAreIdempotent() throws Exception {
        Path project = projectWith(SOURCE);
        analyzerService.analyze(
                new AnalysisRequest(project.toString(), "rev-dup", false));
        AnalysisReport second = analyzerService.analyze(
                new AnalysisRequest(project.toString(), "rev-dup", false));

        List<AnalysisReport> history = reportRepository.history(100);
        long sameRevision = history.stream()
                .filter(report -> report.revision().equals("rev-dup"))
                .count();
        assertEquals(1, sameRevision,
                "repeated analysis must overwrite instead of duplicating records");
        AnalysisReport stored = reportRepository.findByRevision("rev-dup").orElseThrow();
        assertEquals(second.id(), stored.id(),
                "overwriting an old result keeps the deterministic report id");
    }

    @Test
    void concurrentDuplicateSubmissionsReturnOneConsistentResult() throws Exception {
        Path project = projectWith(SOURCE);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> ids = ConcurrentHashMap.newKeySet();
        List<AnalysisReport> reports = new ArrayList<>(java.util.Collections.synchronizedList(
                new ArrayList<>()));

        for (int i = 0; i < 6; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    reports.add(analyzerService.analyze(
                            new AnalysisRequest(project.toString(), "rev-concurrent", false)));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(2, TimeUnit.MINUTES));

        reports.forEach(report -> ids.add(report.id()));
        assertEquals(1, ids.size(),
                "concurrent duplicate analyses must share one report id");
        for (AnalysisReport report : reports) {
            assertBusinessEqual(reports.get(0), report);
        }
        assertEquals(1, reportRepository.history(100).stream()
                .filter(report -> report.revision().equals("rev-concurrent"))
                .count());
    }

    @Test
    void resubmissionAfterSimulatedInterruptKeepsReportId() throws Exception {
        Path project = projectWith(SOURCE);
        AnalysisReport complete = analyzerService.analyze(
                new AnalysisRequest(project.toString(), "rev-restart", false));

        ReportRepository restarted = new ReportRepository(properties);
        restarted.init();
        AnalysisReport rerun = analyzerService.analyze(
                new AnalysisRequest(project.toString(), "rev-restart", true));

        assertEquals(complete.id(), rerun.id(),
                "re-running a batch after restart/interrupt yields the same id");
        AnalysisReport stored = restarted.findByRevision("rev-restart").orElseThrow();
        assertEquals(complete.id(), stored.id());
    }

    private static void assertBusinessEqual(AnalysisReport expected,
                                            AnalysisReport actual) {
        assertEquals(expected.id(), actual.id());
        assertEquals(expected.revision(), actual.revision());
        assertEquals(expected.analyzerVersion(), actual.analyzerVersion());
        assertEquals(expected.configHash(), actual.configHash());
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.files(), actual.files());
        assertEquals(expected.findings(), actual.findings());
        assertEquals(expected.failures(), actual.failures());
        assertEquals(structuralSummary(expected), structuralSummary(actual),
                "structural summary must be identical; run metadata may differ");
    }

    private static Map<String, Double> structuralSummary(AnalysisReport report) {
        Map<String, Double> result = new LinkedHashMap<>();
        report.summary().forEach((key, value) -> {
            if (!RUN_METADATA_KEYS.contains(key)) {
                result.put(key, value);
            }
        });
        return result;
    }
}
