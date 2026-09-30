package com.github.guoyyit.codemetricsjava.tests;

import com.github.guoyyit.codemetricsjava.config.JsonSupport;
import com.github.guoyyit.codemetricsjava.config.MetricsProperties;
import com.github.guoyyit.codemetricsjava.model.AnalysisReport;
import com.github.guoyyit.codemetricsjava.model.AnalysisRequest;
import com.github.guoyyit.codemetricsjava.persistence.IncrementalCache;
import com.github.guoyyit.codemetricsjava.persistence.ReportRepository;
import com.github.guoyyit.codemetricsjava.analysis.AnalyzerService;
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
class RecoveryTest {

    @TempDir
    Path tempDir;

    @Autowired
    AnalyzerService analyzerService;

    @Autowired
    MetricsProperties properties;

    @Test
    void crashedBatchLeavesNoPartialVisibleReportAndSurvivedReportRemains()
            throws Exception {
        Path storage = tempDir.resolve("storage");
        Files.createDirectories(storage);
        properties.setStorageDir(storage.toString());

        Path project = tempDir.resolve("project");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/A.java"),
                "package x; public class A { int v(){return 1;} }");

        analyzerService.analyze(
                new AnalysisRequest(project.toString(), "done", false));

        Path reports = storage.resolve("reports");
        Path staging = reports.resolve("RPT-crashed.staging");
        Files.createDirectories(staging);
        JsonSupport.writeAtomically(staging.resolve("report.json"),
                new java.util.HashMap<String, String>());
        Files.writeString(staging.resolve("status"), "RUNNING");

        assertTrue(Files.exists(staging));

        ReportRepository restarted = new ReportRepository(properties);
        restarted.init();

        assertFalse(Files.exists(staging),
                "staging directory of an unfinished batch must be purged on restart");
        AnalysisReport survived = restarted.findByRevision("done").orElseThrow();
        assertEquals("COMPLETED", survived.status());
        assertEquals(1, survived.files().size());

        IncrementalCache cache = new IncrementalCache(properties);
        cache.init();
        assertTrue(cache.lookup("src/A.java", survived.files().get(0).contentHash(),
                survived.configHash(), survived.analyzerVersion()).isPresent(),
                "incremental cache survives restart");
    }

}
