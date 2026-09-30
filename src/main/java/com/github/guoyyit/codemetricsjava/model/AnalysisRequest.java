package com.github.guoyyit.codemetricsjava.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 分析请求。null 增量参数表示由服务依据是否存在基线自动决定。
 */
public class AnalysisRequest {
    public String rootPath;
    public String revision;
    public String label;
    public String baselineId;
    public Boolean incremental;
    public Integer parallelism;
    public Long memoryLimitBytes;
    public List<String> includes = new ArrayList<>();
    public List<String> excludes = new ArrayList<>();
}
