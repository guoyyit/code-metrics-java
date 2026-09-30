package com.github.guoyyit.codemetricsjava.model;

/**
 * 基线对比中的单项度量变化。
 */
public class MetricDelta {
    public String scope;
    public String qualifiedName;
    public String path;
    public String metric;
    public double baseValue;
    public double targetValue;
    public double delta;
}
