package com.github.guoyyit.codemetricsjava.command;

import com.github.guoyyit.codemetricsjava.model.AnalysisRequest;
import com.github.guoyyit.codemetricsjava.model.AnalysisSnapshot;
import com.github.guoyyit.codemetricsjava.model.SnapshotSummary;
import com.github.guoyyit.codemetricsjava.service.AnalysisService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动时对本仓库自身运行一次分析（codemetrics.self-analyze=true 时生效），用于命令行验收。
 */
@Component
@ConditionalOnProperty(name = "codemetrics.self-analyze", havingValue = "true")
public class SelfAnalyzeRunner implements ApplicationRunner {

    private final AnalysisService analysisService;

    public SelfAnalyzeRunner(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @Override
    public void run(ApplicationArguments args) {
        AnalysisRequest request = new AnalysisRequest();
        request.rootPath = System.getProperty("user.dir");
        request.label = "self-analyze";
        AnalysisSnapshot snapshot = analysisService.analyze(request);
        SnapshotSummary summary = snapshot.summary;
        System.out.println("[code-metrics] 快照: " + summary.snapshotId);
        System.out.println("[code-metrics] 文件: " + summary.fileCount
                + "，解析失败: " + summary.parseErrorCount);
        System.out.println("[code-metrics] LOC: " + summary.loc + "，SLOC: " + summary.sloc);
        System.out.println("[code-metrics] 坏味道: " + summary.findingCount
                + "（生效 " + summary.activeFindingCount
                + "，豁免 " + summary.suppressedCount + "）");
        System.out.println("[code-metrics] 增量: " + summary.incremental
                + (summary.baselineId == null ? "" : "，基线: " + summary.baselineId));
    }
}
