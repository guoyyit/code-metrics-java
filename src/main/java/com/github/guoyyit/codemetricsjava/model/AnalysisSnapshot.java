package com.github.guoyyit.codemetricsjava.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次完整分析批次的持久化结果。快照一经写入即不可变。
 */
public class AnalysisSnapshot {
    public SnapshotSummary summary = new SnapshotSummary();
    public List<FileAnalysisResult> results = new ArrayList<>();
}
