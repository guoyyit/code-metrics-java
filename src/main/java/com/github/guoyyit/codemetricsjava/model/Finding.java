package com.github.guoyyit.codemetricsjava.model;

/**
 * 一次坏味道命中。豁免不删除命中，而是以 suppressed 标记，保证可追溯。
 */
public class Finding {
    public String id;
    public String ruleId;
    public AnalysisLevel level;
    public String path;
    public String typeQualifiedName;
    public String methodSignature;
    public int beginLine;
    public int endLine;
    public String metric;
    public double measuredValue;
    public double threshold;
    public String reason;
    public boolean suppressed;
    public String exemptionId;
}
