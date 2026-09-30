package com.github.guoyyit.codemetricsjava.model;

/**
 * 文件级聚合度量。
 */
public class FileMetrics {
    public String path;
    public int loc;
    public int sloc;
    public int typeCount;
    public int methodCount;
    public int totalComplexity;
    public int maxMethodComplexity;
    public double avgMethodComplexity;
    public int totalFanOut;
    public int maxFanOut;
}
