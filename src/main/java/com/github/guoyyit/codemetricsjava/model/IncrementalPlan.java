package com.github.guoyyit.codemetricsjava.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 增量计划：变更文件重算，未变更文件沿用上一快照结果。
 */
public class IncrementalPlan {
    public String baselineId;
    public boolean incremental;
    public List<String> changedFiles = new ArrayList<>();
    public List<String> unchangedFiles = new ArrayList<>();
}
