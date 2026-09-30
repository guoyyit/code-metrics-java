package com.github.guoyyit.codemetricsjava.tests;

import com.github.guoyyit.codemetricsjava.config.MetricsProperties;
import com.github.guoyyit.codemetricsjava.engine.IncrementalPlanner;
import com.github.guoyyit.codemetricsjava.engine.JavaFileAnalyzer;
import com.github.guoyyit.codemetricsjava.engine.SourceScanner;
import com.github.guoyyit.codemetricsjava.model.AnalysisRequest;
import com.github.guoyyit.codemetricsjava.model.AnalysisSnapshot;
import com.github.guoyyit.codemetricsjava.model.ComparisonResult;
import com.github.guoyyit.codemetricsjava.model.Exemption;
import com.github.guoyyit.codemetricsjava.model.FileAnalysisResult;
import com.github.guoyyit.codemetricsjava.model.Finding;
import com.github.guoyyit.codemetricsjava.model.RuleConfig;
import com.github.guoyyit.codemetricsjava.model.SmellRuleId;
import com.github.guoyyit.codemetricsjava.model.SnapshotSummary;
import com.github.guoyyit.codemetricsjava.rules.RuleEvaluator;
import com.github.guoyyit.codemetricsjava.rules.ScopeMatcher;
import com.github.guoyyit.codemetricsjava.service.AnalysisService;
import com.github.guoyyit.codemetricsjava.service.ComparisonService;
import com.github.guoyyit.codemetricsjava.service.ExemptionService;
import com.github.guoyyit.codemetricsjava.service.RuleConfigService;
import com.github.guoyyit.codemetricsjava.storage.JsonUtil;
import com.github.guoyyit.codemetricsjava.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisIntegrationTest {

    @TempDir
    Path repoRoot;

    @TempDir
    Path dataRoot;

    private StorageService storage;
    private AnalysisService analysisService;
    private ComparisonService comparisonService;
    private RuleConfigService ruleConfigService;
    private ExemptionService exemptionService;

    @BeforeEach
    void setUp() {
        MetricsProperties properties = new MetricsProperties();
        properties.setStorageDir(dataRoot.toString());
        properties.setDefaultParallelism(2);
        storage = new StorageService(properties);
        SourceScanner scanner = new SourceScanner();
        JavaFileAnalyzer analyzer = new JavaFileAnalyzer();
        ScopeMatcher scopeMatcher = new ScopeMatcher();
        RuleEvaluator evaluator = new RuleEvaluator(scopeMatcher);
        IncrementalPlanner planner = new IncrementalPlanner();
        ruleConfigService = new RuleConfigService(storage);
        exemptionService = new ExemptionService(storage);
        analysisService = new AnalysisService(properties, storage, scanner, analyzer, evaluator,
                planner, exemptionService, ruleConfigService);
        comparisonService = new ComparisonService(storage);
    }

    private String writeJava(String relativePath, String className, int extraLines)
            throws Exception {
        Path file = repoRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        StringBuilder body = new StringBuilder();
        body.append("package demo;\n\n");
        body.append("public class ").append(className).append(" {\n");
        for (int i = 0; i < extraLines; i++) {
            body.append("    private int field").append(i).append(" = ").append(i).append(";\n");
        }
        body.append("    public int complex(int a, int b, int c, int d, int e, int f) {\n");
        for (int i = 0; i < 12; i++) {
            body.append("        if (a > ").append(i).append(") {\n");
            body.append("            b += a;\n");
            body.append("        }\n");
        }
        body.append("        return b;\n");
        body.append("    }\n");
        body.append("}\n");
        Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
        return relativePath;
    }

    private AnalysisRequest request(String revision, Boolean incremental) {
        AnalysisRequest request = new AnalysisRequest();
        request.rootPath = repoRoot.toString();
        request.revision = revision;
        request.incremental = incremental;
        return request;
    }

    @Test
    void incrementalResultIsIdenticalToFullReanalysis() throws Exception {
        String path = writeJava("src/main/java/demo/A.java", "A", 3);
        AnalysisSnapshot first = analysisService.analyze(request("v1", false));
        assertEquals(1, first.summary.fileCount);

        // 未做任何改动时增量分析应直接复用，且结果与全量逐项一致
        AnalysisSnapshot incrementalUnchanged = analysisService.analyze(request("v1", true));
        assertEquals(first.summary.snapshotId, incrementalUnchanged.summary.snapshotId);
        assertTrue(incrementalUnchanged.summary.incremental);

        // 修改文件后做增量分析
        writeJava(path, "A", 5);
        AnalysisSnapshot incrementalChanged = analysisService.analyze(request("v2", true));
        assertTrue(incrementalChanged.summary.incremental);
        assertEquals(1, incrementalChangedChangedFileCount(incrementalChanged));

        // 对同一棵树做全量分析，结果必须逐项一致
        AnalysisSnapshot full = analysisService.analyze(request("v2", false));
        assertSnapshotsEqual(full, incrementalChanged);
    }

    private int incrementalChangedChangedFileCount(AnalysisSnapshot snapshot) {
        return snapshot.summary.changedFileCount;
    }

    private void assertSnapshotsEqual(AnalysisSnapshot expected, AnalysisSnapshot actual) {
        assertEquals(expected.results.size(), actual.results.size());
        assertEquals(JsonUtil.toJson(expected.results), JsonUtil.toJson(actual.results));
    }

    @Test
    void thresholdScopeAndSwitchAreConfigurable() throws Exception {
        writeJava("src/main/java/demo/B.java", "B", 0);
        List<RuleConfig> configs = ruleConfigService.getConfigs();
        RuleConfig config = configs.stream()
                .filter(rule -> rule.ruleId.equals(SmellRuleId.COMPLEX_METHOD.name()))
                .findFirst().orElseThrow();
        config.threshold = 100;
        ruleConfigService.update(SmellRuleId.COMPLEX_METHOD.name(), config);

        AnalysisSnapshot snapshot = analysisService.analyze(request("v1", false));
        assertTrue(snapshot.results.get(0).findings.stream()
                .noneMatch(finding -> finding.ruleId.equals(SmellRuleId.COMPLEX_METHOD.name())));

        config.threshold = 5;
        ruleConfigService.update(SmellRuleId.COMPLEX_METHOD.name(), config);
        snapshot = analysisService.analyze(request("v1", false));
        Finding finding = snapshot.results.get(0).findings.stream()
                .filter(item -> item.ruleId.equals(SmellRuleId.COMPLEX_METHOD.name()))
                .findFirst().orElseThrow();
        assertTrue(finding.beginLine > 0);
        assertTrue(finding.reason.contains("阈值"));
        assertTrue(finding.measuredValue >= finding.threshold);
    }

    @Test
    void exemptionPersistsSuppressesAndIsAuditable() throws Exception {
        writeJava("src/main/java/demo/C.java", "C", 0);
        AnalysisSnapshot before = analysisService.analyze(request("v1", false));
        assertFalse(before.results.get(0).findings.isEmpty());
        String targetRule = before.results.get(0).findings.get(0).ruleId;

        Exemption exemption = new Exemption();
        exemption.ruleId = targetRule;
        exemption.filePatterns = List.of("src/main/java/demo/C.java");
        exemption.reason = "误报";
        exemption.createdBy = "tester";
        Exemption saved = exemptionService.create(exemption);
        assertNotNull(saved.id);

        AnalysisSnapshot after = analysisService.analyze(request("v1", false));
        Finding suppressed = after.results.get(0).findings.stream()
                .filter(item -> item.ruleId.equals(targetRule))
                .findFirst().orElseThrow();
        assertTrue(suppressed.suppressed);
        assertEquals(saved.id, suppressed.exemptionId);
        assertEquals(1, after.summary.suppressedCount);
        assertEquals(1, exemptionService.events().size());

        // 撤销后下一次分析重新生效
        exemptionService.revoke(saved.id, "确认有效", "tester");
        AnalysisSnapshot restored = analysisService.analyze(request("v1", false));
        assertFalse(restored.results.get(0).findings.stream()
                .filter(item -> item.ruleId.equals(targetRule))
                .findFirst().orElseThrow().suppressed);
        assertEquals(2, exemptionService.events().size());
    }

    @Test
    void comparisonReportsNewAndRemovedFindingsAndMetricDeltas() throws Exception {
        writeJava("src/main/java/demo/D.java", "D", 0);
        AnalysisSnapshot base = analysisService.analyze(request("v1", false));

        writeJava("src/main/java/demo/D.java", "D", 40);
        AnalysisSnapshot target = analysisService.analyze(request("v2", false));

        ComparisonResult comparison = comparisonService.compare(
                base.summary.snapshotId, target.summary.snapshotId);
        assertFalse(comparison.metricDeltas.isEmpty());
        // 字段增加只会改变度量，坏味道集合变化可能为空或非空，二者集合互不相交
        for (Finding added : comparison.newFindings) {
            assertTrue(comparison.removedFindings.stream()
                    .noneMatch(removed -> removed.id.equals(added.id)));
        }
        assertNotNull(comparison.baseId);
        assertNotNull(comparison.targetId);
    }

    @Test
    void concurrentAnalysesDoNotInterfere() throws Exception {
        writeJava("src/main/java/demo/E1.java", "E1", 0);
        writeJava("src/main/java/demo/E2.java", "E2", 0);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<AnalysisSnapshot>> tasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String revision = "concurrent-" + i;
                tasks.add(() -> {
                    AnalysisRequest callRequest = request(revision, false);
                    return analysisService.analyze(callRequest);
                });
            }
            List<Future<AnalysisSnapshot>> futures = new ArrayList<>();
            for (Callable<AnalysisSnapshot> task : tasks) {
                futures.add(executor.submit(task));
            }
            int index = 0;
            for (Future<AnalysisSnapshot> future : futures) {
                AnalysisSnapshot snapshot = future.get();
                assertEquals(2, snapshot.summary.fileCount);
                assertEquals("concurrent-" + index, snapshot.summary.revision);
                for (FileAnalysisResult result : snapshot.results) {
                    assertEquals(1, result.types.size());
                    assertTrue(result.metrics.typeCount >= 1);
                }
                index++;
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(6, storage.listSnapshots().size());
    }

    @Test
    void brokenFileDoesNotAbortBatchAndIsDistinguishable() throws Exception {
        writeJava("src/main/java/demo/Good.java", "Good", 0);
        Path broken = repoRoot.resolve("src/main/java/demo/Bad.java");
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, "package demo;\npublic class Bad {\n void x( {\n}\n",
                StandardCharsets.UTF_8);

        AnalysisSnapshot snapshot = analysisService.analyze(request("v1", false));
        assertEquals(2, snapshot.summary.fileCount);
        assertEquals(1, snapshot.summary.parseErrorCount);
        assertEquals(1, snapshot.summary.okCount);
        FileAnalysisResult bad = snapshot.results.stream()
                .filter(result -> result.path.endsWith("Bad.java")).findFirst().orElseThrow();
        assertTrue(bad.errorMessage.startsWith("PARSE_ERROR"));
    }

    @Test
    void savedSnapshotsSurviveRestartAndTempFilesAreNotVisible() throws Exception {
        writeJava("src/main/java/demo/F.java", "F", 0);
        AnalysisSnapshot snapshot = analysisService.analyze(request("v1", false));

        // 模拟上次崩溃遗留的未完成临时文件，重启后必须被清理且不可见
        Path temp = dataRoot.resolve("snapshots")
                .resolve("snap-crashed.json.tmp-" + UUID.randomUUID());
        Files.writeString(temp, "{partial", StandardCharsets.UTF_8);

        MetricsProperties restartedProperties = new MetricsProperties();
        restartedProperties.setStorageDir(dataRoot.toString());
        restartedProperties.setDefaultParallelism(1);
        StorageService restartedStorage = new StorageService(restartedProperties);
        SourceScanner scanner = new SourceScanner();
        AnalysisService restarted = new AnalysisService(restartedProperties, restartedStorage,
                scanner, new JavaFileAnalyzer(),
                new RuleEvaluator(new ScopeMatcher()), new IncrementalPlanner(),
                new ExemptionService(restartedStorage), new RuleConfigService(restartedStorage));

        AnalysisSnapshot reloaded = restartedStorage.loadSnapshot(snapshot.summary.snapshotId);
        assertNotNull(reloaded);
        assertEquals(1, restartedStorage.listSnapshots().size());
        assertFalse(Files.exists(temp));

        // 重启后增量分析沿用已持久化结果，仍得到一致输出
        AnalysisSnapshot again = restarted.analyze(request("v1", true));
        assertEquals(snapshot.summary.snapshotId, again.summary.snapshotId);
    }

    @Test
    void selfRepositoryCanBeAnalyzed() throws Exception {
        Path selfRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        AnalysisRequest request = new AnalysisRequest();
        request.rootPath = selfRoot.toString();
        request.incremental = false;
        request.excludes = List.of("target/**", ".codemetrics-data/**");
        AnalysisSnapshot snapshot = analysisService.analyze(request);
        assertTrue(snapshot.summary.fileCount >= 20);
        assertTrue(snapshot.summary.parseErrorCount == 0);
        List<SnapshotSummary> summaries = storage.listSnapshots();
        assertEquals(1, summaries.size());
        // 同一输入重复分析生成相同的确定性快照
        AnalysisSnapshot repeated = analysisService.analyze(request);
        assertEquals(snapshot.summary.snapshotId, repeated.summary.snapshotId);
    }
}
