package com.github.guoyyit.codemetricsjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 可配置项：存储目录、默认并行度、内存上限（估算字节数）与扫描范围。
 */
@ConfigurationProperties(prefix = "codemetrics")
public class MetricsProperties {

    private String storageDir = ".codemetrics-data";
    private int defaultParallelism = Runtime.getRuntime().availableProcessors();
    private long defaultMemoryLimitBytes = 64L * 1024 * 1024;
    private List<String> defaultIncludes = new ArrayList<>();
    private List<String> defaultExcludes = new ArrayList<>();

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    public int getDefaultParallelism() {
        return defaultParallelism;
    }

    public void setDefaultParallelism(int defaultParallelism) {
        this.defaultParallelism = defaultParallelism;
    }

    public long getDefaultMemoryLimitBytes() {
        return defaultMemoryLimitBytes;
    }

    public void setDefaultMemoryLimitBytes(long defaultMemoryLimitBytes) {
        this.defaultMemoryLimitBytes = defaultMemoryLimitBytes;
    }

    public List<String> getDefaultIncludes() {
        return defaultIncludes;
    }

    public void setDefaultIncludes(List<String> defaultIncludes) {
        this.defaultIncludes = defaultIncludes;
    }

    public List<String> getDefaultExcludes() {
        return defaultExcludes;
    }

    public void setDefaultExcludes(List<String> defaultExcludes) {
        this.defaultExcludes = defaultExcludes;
    }
}
