package com.github.guoyyit.codemetricsjava.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 两个快照间坏味道新增/消除与度量趋势。以 findingId 作为稳定身份匹配。
 */
public class ComparisonResult {
    public String baseId;
    public String targetId;
    public List<Finding> newFindings = new ArrayList<>();
    public List<Finding> removedFindings = new ArrayList<>();
    public List<Finding> persistentFindings = new ArrayList<>();
    public List<MetricDelta> metricDeltas = new ArrayList<>();
}
