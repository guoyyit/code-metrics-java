package com.github.guoyyit.codemetricsjava.service;

import com.github.guoyyit.codemetricsjava.model.AnalysisSnapshot;
import com.github.guoyyit.codemetricsjava.model.ComparisonResult;
import com.github.guoyyit.codemetricsjava.model.FileAnalysisResult;
import com.github.guoyyit.codemetricsjava.model.Finding;
import com.github.guoyyit.codemetricsjava.model.MetricDelta;
import com.github.guoyyit.codemetricsjava.model.MethodMetrics;
import com.github.guoyyit.codemetricsjava.model.TypeMetrics;
import com.github.guoyyit.codemetricsjava.storage.StorageService;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 基线对比：新增/消除坏味道与方法级、类型级度量趋势。结果按稳定键排序，可复现。
 */
@Service
public class ComparisonService {

    private final StorageService storage;

    public ComparisonService(StorageService storage) {
        this.storage = storage;
    }

    public ComparisonResult compare(String baseId, String targetId) {
        AnalysisSnapshot base = storage.loadSnapshot(baseId);
        AnalysisSnapshot target = storage.loadSnapshot(targetId);
        if (base == null) {
            throw new IllegalArgumentException("基线快照不存在: " + baseId);
        }
        if (target == null) {
            throw new IllegalArgumentException("目标快照不存在: " + targetId);
        }
        ComparisonResult result = new ComparisonResult();
        result.baseId = baseId;
        result.targetId = targetId;

        Map<String, Finding> baseFindings = toFindingMap(base);
        Map<String, Finding> targetFindings = toFindingMap(target);
        for (Map.Entry<String, Finding> entry : targetFindings.entrySet()) {
            if (!baseFindings.containsKey(entry.getKey())) {
                result.newFindings.add(entry.getValue());
            } else {
                result.persistentFindings.add(entry.getValue());
            }
        }
        for (Map.Entry<String, Finding> entry : baseFindings.entrySet()) {
            if (!targetFindings.containsKey(entry.getKey())) {
                result.removedFindings.add(entry.getValue());
            }
        }
        sortFindings(result.newFindings);
        sortFindings(result.removedFindings);
        sortFindings(result.persistentFindings);

        Map<String, Double> baseMetrics = toMetricMap(base);
        Map<String, Double> targetMetrics = toMetricMap(target);
        for (Map.Entry<String, Double> entry : targetMetrics.entrySet()) {
            Double baseValue = baseMetrics.get(entry.getKey());
            if (baseValue != null && Double.compare(baseValue, entry.getValue()) != 0) {
                result.metricDeltas.add(toDelta(entry.getKey(), baseValue, entry.getValue()));
            }
        }
        result.metricDeltas.sort(Comparator
                .comparing((MetricDelta delta) -> delta.scope)
                .thenComparing(delta -> delta.qualifiedName)
                .thenComparing(delta -> delta.metric));
        return result;
    }

    private Map<String, Finding> toFindingMap(AnalysisSnapshot snapshot) {
        Map<String, Finding> findings = new TreeMap<>();
        for (FileAnalysisResult file : snapshot.results) {
            for (Finding finding : file.findings) {
                findings.put(finding.id, finding);
            }
        }
        return findings;
    }

    private void sortFindings(List<Finding> findings) {
        findings.sort(Comparator
                .comparing((Finding finding) -> finding.path)
                .thenComparingInt(finding -> finding.beginLine)
                .thenComparing(finding -> finding.ruleId));
    }

    private Map<String, Double> toMetricMap(AnalysisSnapshot snapshot) {
        Map<String, Double> values = new TreeMap<>();
        for (FileAnalysisResult file : snapshot.results) {
            for (MethodMetrics method : file.methods) {
                String key = "METHOD|" + method.qualifiedName + "|";
                values.put(key + "loc", (double) method.loc);
                values.put(key + "sloc", (double) method.sloc);
                values.put(key + "complexity", (double) method.complexity);
                values.put(key + "parameterCount", (double) method.parameterCount);
            }
            for (TypeMetrics type : file.types) {
                String key = "TYPE|" + type.qualifiedName + "|";
                values.put(key + "loc", (double) type.loc);
                values.put(key + "sloc", (double) type.sloc);
                values.put(key + "wmc", (double) type.wmc);
                values.put(key + "fanOut", (double) type.fanOut);
                values.put(key + "dit", (double) type.dit);
                values.put(key + "rfc", (double) type.rfc);
                values.put(key + "methodCount", (double) type.methodCount);
            }
        }
        return values;
    }

    private MetricDelta toDelta(String key, double baseValue, double targetValue) {
        String[] parts = key.split("\\|");
        MetricDelta delta = new MetricDelta();
        delta.scope = parts[0];
        delta.qualifiedName = parts[1];
        delta.metric = parts[2];
        delta.baseValue = baseValue;
        delta.targetValue = targetValue;
        delta.delta = targetValue - baseValue;
        return delta;
    }
}
