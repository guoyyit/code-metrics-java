package com.github.guoyyit.codemetricsjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codemetrics")
public class MetricsProperties {

    private String storageDir = "data/codemetrics";
    private int parallelism = 4;
    private long memoryBudgetBytes = 67108864L;
    private String analyzerVersion = "1.0.0";

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public int getParallelism() { return parallelism; }
    public void setParallelism(int parallelism) { this.parallelism = parallelism; }
    public long getMemoryBudgetBytes() { return memoryBudgetBytes; }
    public void setMemoryBudgetBytes(long memoryBudgetBytes) { this.memoryBudgetBytes = memoryBudgetBytes; }
    public String getAnalyzerVersion() { return analyzerVersion; }
    public void setAnalyzerVersion(String analyzerVersion) { this.analyzerVersion = analyzerVersion; }
}
