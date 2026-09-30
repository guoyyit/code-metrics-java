package com.github.guoyyit.codemetricsjava.tests;

import com.github.guoyyit.codemetricsjava.analysis.AnalyzerService;
import com.github.guoyyit.codemetricsjava.analysis.SuppressionService;
import com.github.guoyyit.codemetricsjava.compare.BaselineComparator;
import com.github.guoyyit.codemetricsjava.config.MetricsProperties;
import com.github.guoyyit.codemetricsjava.model.AnalysisReport;
import com.github.guoyyit.codemetricsjava.model.AnalysisRequest;
import com.github.guoyyit.codemetricsjava.model.BaselineComparison;
import com.github.guoyyit.codemetricsjava.model.SmellFinding;
import com.github.guoyyit.codemetricsjava.model.Suppression;
import com.github.guoyyit.codemetricsjava.model.SuppressionRequest;
import com.github.guoyyit.codemetricsjava.persistence.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class SuppressionAndBaselineTest {

    @TempDir
    Path tempDir;

    @Autowired AnalyzerService analyzerService;
    @Autowired SuppressionService suppressionService;
    @Autowired BaselineComparator comparator;
    @Autowired ReportRepository reportRepository;
    @Autowired MetricsProperties properties;

    private Path sourceRoot;

    @BeforeEach
    void setUp() throws Exception {
        Path storage = tempDir.resolve("storage");
        Files.createDirectories(storage);
        properties.setStorageDir(storage.toString());
        sourceRoot = tempDir.resolve("project");
        Path fixtures = Files.exists(Path.of("target/test-classes/fixtures/sample"))
                ? Path.of("target/test-classes/fixtures/sample")
                : Path.of("src/test/resources/fixtures/sample");
        try (var walk = Files.walk(fixtures)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                Path destination = sourceRoot.resolve(fixtures.relativize(path).toString());
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination);
            }
        }
    }

    @Test
    void suppressionPersistsAndHidesFindingInLaterRuns() {
        AnalysisReport before = analyzerService.analyze(
                new AnalysisRequest(sourceRoot.toString(), "before", false));
        SmellFinding target = before.findings().stream()
                .filter(finding -> finding.ruleId().equals("MANY_PARAMETERS"))
                .findFirst().orElseThrow();
        assertFalse(target.suppressed());

        Suppression suppression = suppressionService.grant(new SuppressionRequest(
                target.ruleId(), target.fingerprint(), target.path(),
                "test false positive", "tester"));
        assertTrue(suppressionService.list().stream()
                .anyMatch(item -> item.id().equals(suppression.id())));

        AnalysisReport after = analyzerService.analyze(
                new AnalysisRequest(sourceRoot.toString(), "after", false));
        SmellFinding suppressed = after.findings().stream()
                .filter(finding -> finding.fingerprint().equals(target.fingerprint()))
                .findFirst().orElseThrow();
        assertTrue(suppressed.suppressed());
        assertEquals(suppression.id(), suppressed.suppressionId());
        assertEquals(after.summary().get("activeFindings"),
                before.summary().get("activeFindings") - 1);

        suppressionService.revoke(suppression.id(), "tester");
        AnalysisReport reactivated = analyzerService.analyze(
                new AnalysisRequest(sourceRoot.toString(), "reactivated", false));
        assertTrue(reactivated.findings().stream()
                .filter(finding -> finding.fingerprint().equals(target.fingerprint()))
                .findFirst().orElseThrow().suppressed() == false);
        assertTrue(Files.exists(Path.of(properties.getStorageDir())
                .resolve("audit/suppressions.log")));
    }

    @Test
    void baselineComparisonReportsAddedAndRemovedSmellsAndTrend() throws Exception {
        AnalysisReport baseline = analyzerService.analyze(
                new AnalysisRequest(sourceRoot.toString(), "base", false));

        Path smelly = sourceRoot.resolve("src/com/example/Smelly.java");
        String simplified = """
                package com.example;

                import java.util.ArrayList;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.HashMap;
                import java.util.TreeMap;
                import java.util.HashSet;
                import java.util.LinkedList;
                import java.util.Vector;
                import java.util.Stack;
                import java.util.Queue;
                import java.util.Deque;

                public class Smelly {

                    public String greeting(String name) {
                        return name;
                    }

                    public void useTypes() {
                        List<String> list = new ArrayList<>();
                        Map<String, String> map = new HashMap<>();
                        Map<String, String> linked = new LinkedHashMap<>();
                        Map<String, String> tree = new TreeMap<>();
                        HashSet<String> set = new HashSet<>();
                        LinkedList<String> linkedList = new LinkedList<>();
                        Vector<String> vector = new Vector<>();
                        Stack<String> stack = new Stack<>();
                        Queue<String> queue = null;
                        Deque<String> deque = null;
                        list.add("a");
                        map.put("k", "v");
                        linked.put("k", "v");
                        tree.put("k", "v");
                        set.add("s");
                        linkedList.add("l");
                        vector.add("v");
                        stack.push("s");
                        if (queue instanceof Object) {
                            System.out.println(queue);
                        }
                    }
                }
                """;
        Files.writeString(smelly, simplified);

        AnalysisReport target = analyzerService.analyze(
                new AnalysisRequest(sourceRoot.toString(), "next", false));

        BaselineComparison comparison = comparator.compare(baseline, target);
        assertEquals("base", comparison.baselineRevision());
        assertEquals("next", comparison.targetRevision());
        assertTrue(comparison.removed().stream()
                .anyMatch(finding -> finding.ruleId().equals("MANY_PARAMETERS")));
        assertTrue(comparison.added().isEmpty()
                || comparison.added().stream().noneMatch(finding ->
                finding.ruleId().equals("MANY_PARAMETERS")));
        assertTrue(comparison.metricDeltas().stream()
                        .anyMatch(delta -> delta.scope().equals("TYPE")
                                && delta.metric().equals("weightedMethodComplexity")
                                && delta.delta() < 0),
                "WMC drops after the complex method is simplified");
        assertTrue(comparison.removed().stream()
                .anyMatch(finding -> finding.ruleId().equals("DEEP_NESTING"))
                || comparison.removed().stream()
                .anyMatch(finding -> finding.ruleId().equals("COMPLEX_METHOD")),
                "simplifying the method removes its complexity/nesting smells");
        assertTrue(comparison.metricDeltas().stream()
                        .filter(delta -> delta.scope().equals("METHOD"))
                        .anyMatch(delta -> delta.metric().equals("parameterCount")),
                "changed signature appears as method-level trend delta");

        BaselineComparison repeated = comparator.compare(baseline, target);
        assertEquals(comparison, repeated,
                "comparison is deterministic and reproducible");
    }

    @Test
    void reportsAreReadableFromRepositoryByRevision() {
        analyzerService.analyze(new AnalysisRequest(sourceRoot.toString(), "keep", false));
        AnalysisReport loaded = reportRepository.findByRevision("keep").orElseThrow();
        assertEquals("keep", loaded.revision());
        assertEquals("COMPLETED", loaded.status());
    }
}
