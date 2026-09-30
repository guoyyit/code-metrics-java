package com.github.guoyyit.codemetricsjava.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个源文件的分析结果。解析失败时 status=PARSE_ERROR 且 errorMessage 可区分失败原因。
 */
public class FileAnalysisResult {
    public String path;
    public String sha256;
    public AnalysisStatus status;
    public String errorMessage;
    public FileMetrics metrics;
    public List<TypeMetrics> types = new ArrayList<>();
    public List<MethodMetrics> methods = new ArrayList<>();
    public List<Finding> findings = new ArrayList<>();

    public static FileAnalysisResult error(String path, String sha256, String errorMessage) {
        FileAnalysisResult result = new FileAnalysisResult();
        result.path = path;
        result.sha256 = sha256;
        result.status = AnalysisStatus.PARSE_ERROR;
        result.errorMessage = errorMessage;
        return result;
    }
}
