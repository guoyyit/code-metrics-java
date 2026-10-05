package com.github.guoyyit.codemetricsjava.compare;

import com.github.guoyyit.codemetricsjava.model.AnalysisReport;
import com.github.guoyyit.codemetricsjava.model.BaselineComparison;
import com.github.guoyyit.codemetricsjava.model.FileMetrics;
import com.github.guoyyit.codemetricsjava.model.MethodMetrics;
import com.github.guoyyit.codemetricsjava.model.SmellFinding;
import com.github.guoyyit.codemetricsjava.model.TypeMetrics;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares two completed reports deterministically.
 *
 * <p>Findings are matched by their stable fingerprint (rule + path +
 * type/member), so the same logical finding reported at a shifted line is
 * still recognized instead of appearing as removed+added. Metric deltas are
 * aligned by fully qualified type name or {@code type#methodName} (signatures
 * are intentionally ignored so a parameter change is a trend, not a rename).
 * Report-level deltas cover structural metrics only; run metadata stays in
 * the summary snapshots. Suppressed findings are excluded from the
 * added/removed sets.
 */
@Component
public class BaselineComparator {

    /**
     * Report-level summary keys that describe code structure and therefore
     * participate in the metric change list. Every other summary key is
     * run metadata (file counts, cache reuse, mode flag, finding counters)
     * and stays only in the two {@code SummarySnapshot}s.
     */
    private static final Set<String> STRUCTURAL_SUMMARY_METRICS = Set.of(
            "sourceLinesOfCode",
            "totalMethods",
            "totalParameters",
            "totalComplexity",
            "maxComplexity",
            "totalFanOut");

    public BaselineComparison compare(AnalysisReport baseline, AnalysisReport target) {
        Map<String, SmellFinding> baseFindings = activeByFingerprint(baseline);
        Map<String, SmellFinding> targetFindings = activeByFingerprint(target);
        List<SmellFinding> added = diff(baseFindings, targetFindings, true);
        List<SmellFinding> removed = diff(baseFindings, targetFindings, false);

        List<BaselineComparison.MetricDelta> deltas = new ArrayList<>();
        deltas.addAll(compareTypes(baseline, target));
        deltas.addAll(compareMethods(baseline, target));
        deltas.addAll(compareSummaries(baseline, target));
        deltas.sort(Comparator
                .comparing(BaselineComparison.MetricDelta::scope)
                .thenComparing(BaselineComparison.MetricDelta::metric)
                .thenComparing(BaselineComparison.MetricDelta::qualifiedName));

        return new BaselineComparison(
                baseline.revision(),
                target.revision(),
                List.copyOf(added),
                List.copyOf(removed),
                List.copyOf(deltas),
                new BaselineComparison.SummarySnapshot(
                        baseline.revision(), baseline.summary()),
                new BaselineComparison.SummarySnapshot(
                        target.revision(), target.summary()));
    }

    private Map<String, SmellFinding> activeByFingerprint(AnalysisReport report) {
        Map<String, SmellFinding> map = new LinkedHashMap<>();
        for (SmellFinding finding : report.findings()) {
            if (!finding.suppressed()) {
                map.putIfAbsent(finding.fingerprint(), finding);
            }
        }
        return map;
    }

    private List<SmellFinding> diff(Map<String, SmellFinding> base,
                                    Map<String, SmellFinding> other,
                                    boolean addedView) {
        List<SmellFinding> result = new ArrayList<>();
        // added: keys present in target but absent in baseline;
        // removed: keys present in baseline but absent in target.
        Map<String, SmellFinding> reference = addedView ? other : base;
        Map<String, SmellFinding> compareAgainst = addedView ? base : other;
        for (Map.Entry<String, SmellFinding> entry : reference.entrySet()) {
            if (!compareAgainst.containsKey(entry.getKey())) {
                result.add(entry.getValue());
            }
        }
        result.sort(Comparator
                .comparing(SmellFinding::path)
                .thenComparing(finding ->
                        finding.location() == null ? 0 : finding.location().startLine())
                .thenComparing(SmellFinding::ruleId)
                .thenComparing(SmellFinding::fingerprint));
        return result;
    }

    private List<BaselineComparison.MetricDelta> compareTypes(
            AnalysisReport baseline, AnalysisReport target) {
        Map<String, TypeMetrics> base = types(baseline);
        Map<String, TypeMetrics> other = types(target);
        List<BaselineComparison.MetricDelta> deltas = new ArrayList<>();
        for (String name : union(base.keySet(), other.keySet())) {
            TypeMetrics left = base.get(name);
            TypeMetrics right = other.get(name);
            delta(deltas, "TYPE", name, "linesOfCode",
                    left == null ? 0 : left.linesOfCode(),
                    right == null ? 0 : right.linesOfCode());
            delta(deltas, "TYPE", name, "methodCount",
                    left == null ? 0 : left.methodCount(),
                    right == null ? 0 : right.methodCount());
            delta(deltas, "TYPE", name, "weightedMethodComplexity",
                    left == null ? 0 : left.weightedMethodComplexity(),
                    right == null ? 0 : right.weightedMethodComplexity());
            delta(deltas, "TYPE", name, "fanOut",
                    left == null ? 0 : left.fanOut(),
                    right == null ? 0 : right.fanOut());
        }
        return deltas;
    }

    private List<BaselineComparison.MetricDelta> compareMethods(
            AnalysisReport baseline, AnalysisReport target) {
        Map<String, MethodMetrics> base = methods(baseline);
        Map<String, MethodMetrics> other = methods(target);
        List<BaselineComparison.MetricDelta> deltas = new ArrayList<>();
        for (String name : union(base.keySet(), other.keySet())) {
            MethodMetrics left = base.get(name);
            MethodMetrics right = other.get(name);
            delta(deltas, "METHOD", name, "linesOfCode",
                    left == null ? 0 : left.linesOfCode(),
                    right == null ? 0 : right.linesOfCode());
            delta(deltas, "METHOD", name, "cyclomaticComplexity",
                    left == null ? 0 : left.cyclomaticComplexity(),
                    right == null ? 0 : right.cyclomaticComplexity());
            delta(deltas, "METHOD", name, "parameterCount",
                    left == null ? 0 : left.parameterCount(),
                    right == null ? 0 : right.parameterCount());
            delta(deltas, "METHOD", name, "maxNestingDepth",
                    left == null ? 0 : left.maxNestingDepth(),
                    right == null ? 0 : right.maxNestingDepth());
        }
        return deltas;
    }

    private List<BaselineComparison.MetricDelta> compareSummaries(
            AnalysisReport baseline, AnalysisReport target) {
        List<BaselineComparison.MetricDelta> deltas = new ArrayList<>();
        Set<String> metrics = new LinkedHashSet<>(STRUCTURAL_SUMMARY_METRICS);
        metrics.retainAll(unionKeys(baseline.summary().keySet(), target.summary().keySet()));
        for (String metric : metrics) {
            double left = baseline.summary().getOrDefault(metric, 0.0);
            double right = target.summary().getOrDefault(metric, 0.0);
            delta(deltas, "REPORT", "", metric, left, right);
        }
        return deltas;
    }

    private void delta(List<BaselineComparison.MetricDelta> sink, String scope,
                       String qualifiedName, String metric,
                       double baselineValue, double targetValue) {
        double change = targetValue - baselineValue;
        if (change != 0) {
            sink.add(new BaselineComparison.MetricDelta(
                    metric, scope, qualifiedName,
                    baselineValue, targetValue, change,
                    change > 0 ? "UP" : "DOWN"));
        }
    }

    private Set<String> unionKeys(Set<String> left, Set<String> right) {
        Set<String> all = new LinkedHashSet<>(left);
        all.addAll(right);
        return all;
    }

    private Map<String, TypeMetrics> types(AnalysisReport report) {
        Map<String, TypeMetrics> map = new LinkedHashMap<>();
        for (FileMetrics file : report.files()) {
            for (TypeMetrics type : file.types()) {
                map.putIfAbsent(type.qualifiedName(), type);
            }
        }
        return map;
    }

    private Map<String, MethodMetrics> methods(AnalysisReport report) {
        Map<String, MethodMetrics> map = new LinkedHashMap<>();
        for (FileMetrics file : report.files()) {
            for (TypeMetrics type : file.types()) {
                for (MethodMetrics method : type.methods()) {
                    map.putIfAbsent(matchKey(method), method);
                }
            }
        }
        return map;
    }

    private String matchKey(MethodMetrics method) {
        String fullId = method.id();
        int hash = fullId.indexOf('#');
        if (hash < 0) {
            return fullId;
        }
        int parenthesis = fullId.indexOf('(', hash);
        if (parenthesis < 0) {
            return fullId;
        }
        return fullId.substring(0, hash + 1) + method.name();
    }

    private List<String> union(java.util.Set<String> left, java.util.Set<String> right) {
        List<String> all = new ArrayList<>(left);
        for (String value : right) {
            if (!left.contains(value)) {
                all.add(value);
            }
        }
        all.sort(String::compareTo);
        return all;
    }
}
