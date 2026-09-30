package com.github.guoyyit.codemetricsjava.model;

/**
 * 快照摘要，用于列表展示，不含逐文件明细。
 */
public class SnapshotSummary {
    public String snapshotId;
    public String revision;
    public String label;
    public String createdAt;
    public long durationMillis;
    public boolean incremental;
    public String baselineId;
    public String configFingerprint;
    public int changedFileCount;
    public int fileCount;
    public int okCount;
    public int parseErrorCount;
    public int findingCount;
    public int activeFindingCount;
    public int suppressedCount;
    public int loc;
    public int sloc;
}
