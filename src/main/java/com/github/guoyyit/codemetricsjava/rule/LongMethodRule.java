package com.github.guoyyit.codemetricsjava.rule;

import com.github.guoyyit.codemetricsjava.model.FileMetrics;
import com.github.guoyyit.codemetricsjava.model.RuleConfig;
import com.github.guoyyit.codemetricsjava.model.SmellFinding;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class LongMethodRule extends AbstractRule {
    @Override public String id() { return "LONG_METHOD"; }
    @Override public String defaultSeverity() { return "MAJOR"; }
    @Override public double defaultThreshold() { return 30; }
    @Override public String defaultScope() { return "METHOD"; }

    @Override
    protected String defaultReasonTemplate() {
        return "方法 {type}#{method} 行数 {value} 超过阈值 {threshold}";
    }

    @Override
    protected void collect(FileMetrics file, RuleConfig.RuleSettings settings,
                           List<SmellFinding> sink) {
        for (var type : file.types()) {
            for (var method : type.methods()) {
                if (method.linesOfCode() > threshold(settings)) {
                    sink.add(methodFinding(file, settings, type, method,
                            "linesOfCode", method.linesOfCode()));
                }
            }
        }
    }
}
