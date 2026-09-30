package com.github.guoyyit.codemetricsjava.tests;

import com.github.guoyyit.codemetricsjava.analysis.AnalyzerService;
import com.github.guoyyit.codemetricsjava.config.MetricsProperties;
import com.github.guoyyit.codemetricsjava.model.AnalysisReport;
import com.github.guoyyit.codemetricsjava.model.AnalysisRequest;
import com.github.guoyyit.codemetricsjava.model.FileFailure;
import com.github.guoyyit.codemetricsjava.model.SmellFinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "codemetrics.parallelism=3")
class AnalyzerIntegrationTest {

    @TempDir
    Path tempDir;

    @Autowired
    AnalyzerService analyzerService;

    @Autowired
    MetricsProperties properties;

    private Path sourceRoot;

    @BeforeEach
    void setUp() throws Exception {
        Path storage = tempDir.resolve("storage");
        Files.createDirectories(storage);
        System.setProperty("CODEMETRICS_HOME", storage.toString());
        properties.setStorageDir(storage.toString());
        sourceRoot = tempDir.resolve("project");
        copyFixtureTree(sourceRoot);
    }

    @Test
    void incrementalAndFullAnalysisAreItemWiseIdentical() {
        AnalysisRequest full = new AnalysisRequest(
                sourceRoot.toString(), "v-full", false);
        AnalysisRequest incremental = new AnalysisRequest(
                sourceRoot.toString(), "v-inc", true);

        AnalysisReport fullReport = analyzerService.analyze(full);
        AnalysisReport incrementalReport = analyzerService.analyze(incremental);

        assertEquals(fullReport.files().size(), incrementalReport.files().size());
        assertEquals(fullReport.files(), incrementalReport.files(),
                "reused cached metrics must be identical item by item");
        assertEquals(fullReport.findings().stream().map(SmellFinding::fingerprint).toList(),
                incrementalReport.findings().stream().map(SmellFinding::fingerprint).toList());
        assertEquals(fullReport.failures(), incrementalReport.failures());
        assertEquals(fullReport.summary().get("activeFindings"),
                incrementalReport.summary().get("activeFindings"));
    }

    @Test
    void incrementalRecomputesOnlyChangedFile() throws Exception {
        analyzerService.analyze(new AnalysisRequest(
                sourceRoot.toString(), "v1", true));

        Path clean = sourceRoot.resolve("src/com/example/Clean.java");
        Files.writeString(clean, Files.readString(clean)
                .replace("return value > 0;",
                        "if (value > 0) { return true; } return false;"));

        AnalysisReport second = analyzerService.analyze(new AnalysisRequest(
                sourceRoot.toString(), "v2", true));

        assertEquals(3, second.summary().get("totalFiles").intValue());
        assertEquals(1, second.summary().get("reusedFiles").intValue(),
                "only unchanged files are reused");
        assertEquals(2, second.summary().get("analyzedFiles").intValue());
    }

    @Test
    void parseFailureDoesNotStopAnalysisAndIsDistinguishable() {
        AnalysisReport report = analyzerService.analyze(new AnalysisRequest(
                sourceRoot.toString(), "v-fail", false));

        List<String> failedPaths = report.failures().stream()
                .map(FileFailure::path).toList();
        assertTrue(failedPaths.contains("src/com/example/Broken.java"));
        FileFailure failure = report.failures().stream()
                .filter(item -> item.path().contains("Broken.java"))
                .findFirst().orElseThrow();
        assertEquals("PARSE_ERROR", failure.reason());
        assertTrue(failure.detail().contains("["),
                "detail carries parser line information");
        assertEquals(2, report.summary().get("analyzedFiles").intValue());
        assertEquals(1, report.summary().get("failedFiles").intValue());
    }

    @Test
    void concurrentAnalysesDoNotCrossTalk() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        Set<List<String>> fingerprintSets = ConcurrentHashMap.newKeySet();
        Set<Integer> findingCounts = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < 6; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    AnalysisReport report = analyzerService.analyze(new AnalysisRequest(
                            sourceRoot.toString(), "concurrent-" + index, false));
                    fingerprintSets.add(report.findings().stream()
                            .map(SmellFinding::fingerprint)
                            .collect(Collectors.toList()));
                    findingCounts.add(report.findings().size());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(2, TimeUnit.MINUTES));

        assertEquals(1, fingerprintSets.size(),
                "all concurrent runs must observe identical findings");
        assertEquals(1, findingCounts.size());
        assertFalse(findingCounts.contains(0));
    }

    private void copyFixtureTree(Path target) throws Exception {
        Path fixtures = Path.of("target/test-classes/fixtures/sample");
        if (!Files.exists(fixtures)) {
            fixtures = Path.of("src/test/resources/fixtures/sample");
        }
        try (var walk = Files.walk(fixtures)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                Path relative = fixtures.relativize(path);
                Path destination = target.resolve(relative.toString());
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination);
            }
        }
    }
}
