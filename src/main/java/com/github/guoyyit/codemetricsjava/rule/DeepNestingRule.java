package com.github.guoyyit.codemetricsjava.rule;

import com.github.guoyyit.codemetricsjava.model.FileMetrics;
import com.github.guoyyit.codemetricsjava.model.RuleConfig;
import com.github.guoyyit.codemetricsjava.model.SmellFinding;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DeepNestingRule extends AbstractRule {
    @Override public String id() { return "DEEP_NESTING"; }
    @Override public String defaultSeverity() { return "MAJOR"; }
    @Override public double defaultThreshold() { return 3; }
    @Override public String defaultScope() { return "METHOD"; }
    @Override
    protected String defaultReasonTemplate() {
        return "方法 {type}#{method} 最大嵌套深度 {value} 超过阈值 {threshold}";
    }

    @Override
    protected void collect(FileMetrics file, RuleConfig.RuleSettings settings,
                           List<SmellFinding> sink) {
        for (var type : file.types()) {
            for (var method : type.methods()) {
                if (method.maxNestingDepth() > threshold(settings)) {
                    sink.add(methodFinding(file, settings, type, method,
                            "maxNestingDepth", method.maxNestingDepth()));
                }
            }
        }
    }
}
