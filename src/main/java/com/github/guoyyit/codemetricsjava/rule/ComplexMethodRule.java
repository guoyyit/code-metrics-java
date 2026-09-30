package com.github.guoyyit.codemetricsjava.rule;

import com.github.guoyyit.codemetricsjava.model.FileMetrics;
import com.github.guoyyit.codemetricsjava.model.RuleConfig;
import com.github.guoyyit.codemetricsjava.model.SmellFinding;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ComplexMethodRule extends AbstractRule {
    @Override public String id() { return "COMPLEX_METHOD"; }
    @Override public String defaultSeverity() { return "CRITICAL"; }
    @Override public double defaultThreshold() { return 10; }
    @Override public String defaultScope() { return "METHOD"; }
    @Override
    protected String defaultReasonTemplate() {
        return "方法 {type}#{method} 圈复杂度 {value} 超过阈值 {threshold}";
    }

    @Override
    protected void collect(FileMetrics file, RuleConfig.RuleSettings settings,
                           List<SmellFinding> sink) {
        for (var type : file.types()) {
            for (var method : type.methods()) {
                if (method.cyclomaticComplexity() > threshold(settings)) {
                    sink.add(methodFinding(file, settings, type, method,
                            "cyclomaticComplexity", method.cyclomaticComplexity()));
                }
            }
        }
    }
}
