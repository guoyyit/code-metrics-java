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
import java.util.List;
import java.util.Map;

/**
 * Compares two completed reports deterministically.
 *
 * <p>Findings are matched by their stable fingerprint (rule + path +
 * type/member), so the same logical finding reported at a shifted line is
 * still recognized instead of appearing as removed+added. Metric deltas are
 * aligned by fully qualified type name or method id. Suppressed findings
 * are excluded from the added/removed sets.
 */
@Component
public class BaselineComparator {

    /**
     * Report-level summary keys that describe code structure and are
     * therefore eligible for the metric-delta list. Runtime metadata such as
     * scanned/analyzed/failed/reused file counts, the incremental flag and
     * smell hit/exemption counts are deliberately excluded even though they
     * remain present in the {@code baseline}/{@code target} snapshots.
     */
    private static final List<String> STRUCTURAL_REPORT_METRICS = List.of(
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
        Map<String, List<MethodMetrics>> base = methods(baseline);
        Map<String, List<MethodMetrics>> other = methods(target);
        List<BaselineComparison.MetricDelta> deltas = new ArrayList<>();
        for (String name : union(base.keySet(), other.keySet())) {
            List<MethodMetrics> leftOverloads = base.getOrDefault(name, List.of());
            List<MethodMetrics> rightOverloads = other.getOrDefault(name, List.of());
            int pairs = Math.max(leftOverloads.size(), rightOverloads.size());
            for (int i = 0; i < pairs; i++) {
                MethodMetrics left = i < leftOverloads.size() ? leftOverloads.get(i) : null;
                MethodMetrics right = i < rightOverloads.size() ? rightOverloads.get(i) : null;
                compareOneMethod(deltas, name, left, right);
            }
        }
        return deltas;
    }

    private void compareOneMethod(List<BaselineComparison.MetricDelta> deltas,
                                  String name, MethodMetrics left, MethodMetrics right) {
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

    private List<BaselineComparison.MetricDelta> compareSummaries(
            AnalysisReport baseline, AnalysisReport target) {
        List<BaselineComparison.MetricDelta> deltas = new ArrayList<>();
        // Iterate the fixed structural allowlist (never raw summary keys) so
        // runtime metadata can never leak into the delta list.
        for (String metric : STRUCTURAL_REPORT_METRICS) {
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
                    change > 0 ? BaselineComparison.MetricDelta.UP
                            : BaselineComparison.MetricDelta.DOWN));
        }
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

    private Map<String, List<MethodMetrics>> methods(AnalysisReport report) {
        Map<String, List<MethodMetrics>> map = new LinkedHashMap<>();
        for (FileMetrics file : report.files()) {
            for (TypeMetrics type : file.types()) {
                for (MethodMetrics method : type.methods()) {
                    // Match by qualified type + simple method name rather
                    // than the signature-bearing id, so adding or removing a
                    // parameter surfaces as a parameterCount delta on the
                    // same method instead of removed+added. Overloads are
                    // paired in signature order.
                    String key = method.location() == null
                            ? method.name()
                            : method.location().typeName() + "#" + method.name();
                    map.computeIfAbsent(key, ignored -> new ArrayList<>())
                            .add(method);
                }
            }
        }
        map.values().forEach(list ->
                list.sort(Comparator.comparing(MethodMetrics::id)));
        return map;
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
