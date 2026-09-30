package com.github.guoyyit.codemetricsjava.engine;

import com.github.guoyyit.codemetricsjava.model.AnalysisStatus;
import com.github.guoyyit.codemetricsjava.model.AnalysisSnapshot;
import com.github.guoyyit.codemetricsjava.model.IncrementalPlan;
import com.github.guoyyit.codemetricsjava.model.FileAnalysisResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 依据文件 sha256 与基线快照比较，决定重算/沿用集合。
 */
@Component
public class IncrementalPlanner {

    public IncrementalPlan plan(AnalysisSnapshot baseline, List<SourceFile> currentFiles) {
        IncrementalPlan plan = new IncrementalPlan();
        plan.incremental = baseline != null;
        plan.baselineId = baseline == null ? null : baseline.summary.snapshotId;

        Map<String, FileAnalysisResult> previous = new LinkedHashMap<>();
        if (baseline != null) {
            for (FileAnalysisResult result : baseline.results) {
                previous.put(result.path, result);
            }
        }
        for (SourceFile sourceFile : currentFiles) {
            FileAnalysisResult previousResult = previous.get(sourceFile.path);
            boolean unchanged = previousResult != null
                    && previousResult.status == AnalysisStatus.OK
                    && sourceFile.sha256.equals(previousResult.sha256);
            if (unchanged) {
                plan.unchangedFiles.add(sourceFile.path);
            } else {
                plan.changedFiles.add(sourceFile.path);
            }
        }
        return plan;
    }
}
