package com.github.guoyyit.codemetricsjava.tests;
import com.github.guoyyit.codemetricsjava.analysis.AnalyzerService;
import com.github.guoyyit.codemetricsjava.compare.BaselineComparator;
import com.github.guoyyit.codemetricsjava.config.MetricsProperties;
import com.github.guoyyit.codemetricsjava.model.AnalysisReport;
import com.github.guoyyit.codemetricsjava.model.AnalysisRequest;
import com.github.guoyyit.codemetricsjava.model.BaselineComparison;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class MetricTrendComparisonTest {

    private static final String BASELINE = """
            package demo;

            import java.util.ArrayList;
            import java.util.List;

            public class OrderService {

                public int add(int left, int right) {
                    return left + right;
                }

                public void seed(List<String> items) {
                    ArrayList<String> copy = new ArrayList<>(items);
                    copy.add("seed");
                }
            }
            """;

    private static final String TARGET = """
            package demo;

            import java.util.List;

            public class OrderService {

                public int add(int left, int right, int carry) {
                    return left + right + carry;
                }

                public void seed(List<String> items) {
                    List<String> copy = List.copyOf(items);
                }
            }
            """;

    @TempDir
    Path tempDir;

    @Autowired
    AnalyzerService analyzerService;

    @Autowired
    BaselineComparator comparator;

    @Autowired
    MetricsProperties properties;

    private Path storage;

    @BeforeEach
    void setUp() throws Exception {
        storage = tempDir.resolve("storage");
        Files.createDirectories(storage);
        properties.setStorageDir(storage.toString());
    }

    private AnalysisReport analyze(String revision, String code) throws Exception {
        Path project = tempDir.resolve("project-" + revision);
        Files.createDirectories(project.resolve("src/demo"));
        Files.writeString(project.resolve("src/demo/OrderService.java"), code);
        return analyzerService.analyze(
                new AnalysisRequest(project.toString(), revision, false));
    }

    @Test
    void metricDeltasCaptureDirectionAndValueAcrossAllScopes() throws Exception {
        AnalysisReport baseline = analyze("base", BASELINE);
        AnalysisReport target = analyze("next", TARGET);

        BaselineComparison comparison = comparator.compare(baseline, target);
        List<BaselineComparison.MetricDelta> deltas = comparison.metricDeltas();

        BaselineComparison.MetricDelta parameterDelta = findDelta(
                deltas, "METHOD", "demo.OrderService#add", "parameterCount")
                .orElseThrow(() -> new AssertionError(
                        "parameter count delta must be emitted: " + deltas));
        assertEquals(2.0, parameterDelta.baselineValue());
        assertEquals(3.0, parameterDelta.targetValue());
        assertEquals(1.0, parameterDelta.delta());
        assertEquals("UP", parameterDelta.direction(),
                "adding a parameter is an increase of exactly one");

        BaselineComparison.MetricDelta typeFanOut = findDelta(
                deltas, "TYPE", "demo.OrderService", "fanOut")
                .orElseThrow(() -> new AssertionError(
                        "type fan-out delta must be emitted: " + deltas));
        assertEquals(2.0, typeFanOut.baselineValue());
        assertEquals(1.0, typeFanOut.targetValue());
        assertEquals(-1.0, typeFanOut.delta());
        assertEquals("DOWN", typeFanOut.direction(),
                "removing an external type reference lowers coupling");

        BaselineComparison.MetricDelta reportFanOut = findDelta(
                deltas, "REPORT", "", "totalFanOut")
                .orElseThrow(() -> new AssertionError(
                        "report fan-out total must move with type fan-out: " + deltas));
        assertEquals(-1.0, reportFanOut.delta());
        assertEquals("DOWN", reportFanOut.direction());

        BaselineComparison.MetricDelta reportParameters = findDelta(
                deltas, "REPORT", "", "totalParameters").orElseThrow();
        assertEquals(1.0, reportParameters.delta());
        assertEquals("UP", reportParameters.direction());
    }

    @Test
    void runMetadataNeverAppearsInMetricDeltas() throws Exception {
        AnalysisReport baseline = analyze("base-meta", BASELINE);
        AnalysisReport target = analyze("next-meta", TARGET);

        BaselineComparison comparison = comparator.compare(baseline, target);
        List<String> metadataMetrics = List.of(
                "totalFiles", "analyzedFiles", "failedFiles", "reusedFiles",
                "incremental", "activeFindings", "suppressedFindings");
        for (BaselineComparison.MetricDelta delta : comparison.metricDeltas()) {
            assertFalse(metadataMetrics.contains(delta.metric()),
                    "run metadata must not be a metric change: " + delta.metric());
        }

        assertTrue(comparison.baseline().values().containsKey("reusedFiles"));
        assertTrue(comparison.baseline().values().containsKey("incremental"));
        assertTrue(comparison.target().values().containsKey("activeFindings"));
        assertTrue(comparison.target().values().containsKey("totalFiles"));
    }

    @Test
    void repeatedComparisonsAreItemWiseAndOrderIdentical() throws Exception {
        AnalysisReport baseline = analyze("base-repeat", BASELINE);
        AnalysisReport target = analyze("next-repeat", TARGET);

        BaselineComparison first = comparator.compare(baseline, target);
        BaselineComparison second = comparator.compare(baseline, target);

        assertEquals(first, second);
        List<BaselineComparison.MetricDelta> deltas = first.metricDeltas();
        for (int i = 1; i < deltas.size(); i++) {
            BaselineComparison.MetricDelta previous = deltas.get(i - 1);
            BaselineComparison.MetricDelta current = deltas.get(i);
            int byScope = previous.scope().compareTo(current.scope());
            assertTrue(byScope <= 0, "deltas are ordered by scope, metric and name");
        }
    }

    private Optional<BaselineComparison.MetricDelta> findDelta(
            List<BaselineComparison.MetricDelta> deltas, String scope,
            String qualifiedName, String metric) {
        return deltas.stream()
                .filter(delta -> delta.scope().equals(scope))
                .filter(delta -> delta.qualifiedName().equals(qualifiedName))
                .filter(delta -> delta.metric().equals(metric))
                .findFirst();
    }
}
