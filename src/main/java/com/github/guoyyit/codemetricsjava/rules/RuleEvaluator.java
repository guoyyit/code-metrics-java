package com.github.guoyyit.codemetricsjava.rules;

import com.github.guoyyit.codemetricsjava.model.AnalysisLevel;
import com.github.guoyyit.codemetricsjava.model.AnalysisStatus;
import com.github.guoyyit.codemetricsjava.model.FileAnalysisResult;
import com.github.guoyyit.codemetricsjava.model.Finding;
import com.github.guoyyit.codemetricsjava.model.MethodMetrics;
import com.github.guoyyit.codemetricsjava.model.RuleConfig;
import com.github.guoyyit.codemetricsjava.model.SmellRuleId;
import com.github.guoyyit.codemetricsjava.model.TypeMetrics;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 依据可配置规则把文件度量转换为坏味道命中。
 */
@Component
public class RuleEvaluator {

    private final ScopeMatcher scopeMatcher;

    public RuleEvaluator(ScopeMatcher scopeMatcher) {
        this.scopeMatcher = scopeMatcher;
    }

    public void evaluate(FileAnalysisResult file, List<RuleConfig> configs) {
        if (file.status == AnalysisStatus.PARSE_ERROR) {
            return;
        }
        Map<String, RuleConfig> configByRuleId = configs.stream()
                .collect(Collectors.toMap(config -> config.ruleId, Function.identity()));
        file.findings.clear();
        for (SmellRuleId rule : SmellRuleId.values()) {
            RuleConfig config = configByRuleId.get(rule.name());
            if (config == null || !config.enabled
                    || !scopeMatcher.matches(file.path, config.include, config.exclude)) {
                continue;
            }
            if (rule.getLevel() == AnalysisLevel.METHOD) {
                file.methods.stream()
                        .sorted(Comparator.comparing(method -> method.beginLine))
                        .filter(method -> measuredValue(rule, method) >= config.threshold)
                        .map(method -> toFinding(rule, config, file, method, null))
                        .forEach(file.findings::add);
            } else {
                file.types.stream()
                        .sorted(Comparator.comparing(type -> type.beginLine))
                        .filter(type -> measuredValue(rule, type) >= config.threshold)
                        .map(type -> toFinding(rule, config, file, null, type))
                        .forEach(file.findings::add);
            }
        }
        file.findings.sort(Comparator
                .comparingInt((Finding finding) -> finding.beginLine)
                .thenComparing(finding -> finding.ruleId));
    }

    private double measuredValue(SmellRuleId rule, MethodMetrics method) {
        return switch (rule.getMetric()) {
            case "loc" -> method.loc;
            case "complexity" -> method.complexity;
            case "parameterCount" -> method.parameterCount;
            default -> 0;
        };
    }

    private double measuredValue(SmellRuleId rule, TypeMetrics type) {
        return switch (rule.getMetric()) {
            case "loc" -> type.loc;
            case "wmc" -> type.wmc;
            case "fanOut" -> type.fanOut;
            default -> 0;
        };
    }

    private Finding toFinding(SmellRuleId rule, RuleConfig config, FileAnalysisResult file,
                              MethodMetrics method, TypeMetrics type) {
        Finding finding = new Finding();
        finding.ruleId = rule.name();
        finding.level = rule.getLevel();
        finding.path = file.path;
        finding.metric = rule.getMetric();
        finding.threshold = config.threshold;
        finding.reason = rule.getDescription() + "（阈值 " + format(config.threshold) + "）";
        if (method != null) {
            finding.typeQualifiedName = enclosingTypeName(method);
            finding.methodSignature = method.signature;
            finding.beginLine = method.beginLine;
            finding.endLine = method.endLine;
            finding.measuredValue = measuredValue(rule, method);
            finding.id = file.path + "#" + method.qualifiedName + "#" + rule.name();
        } else {
            finding.typeQualifiedName = type.qualifiedName;
            finding.beginLine = type.beginLine;
            finding.endLine = type.endLine;
            finding.measuredValue = measuredValue(rule, type);
            finding.id = file.path + "#" + type.qualifiedName + "#" + rule.name();
        }
        finding.reason = rule.getDescription() + "，实测 " + format(finding.measuredValue)
                + "，阈值 " + format(config.threshold);
        return finding;
    }

    private String enclosingTypeName(MethodMetrics method) {
        int separator = method.qualifiedName.indexOf('#');
        return separator > 0 ? method.qualifiedName.substring(0, separator) : method.qualifiedName;
    }

    private String format(double value) {
        if (value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
