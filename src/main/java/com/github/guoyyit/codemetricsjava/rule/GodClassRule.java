package com.github.guoyyit.codemetricsjava.rule;

import com.github.guoyyit.codemetricsjava.model.FileMetrics;
import com.github.guoyyit.codemetricsjava.model.RuleConfig;
import com.github.guoyyit.codemetricsjava.model.SmellFinding;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GodClassRule extends AbstractRule {
    @Override public String id() { return "GOD_CLASS"; }
    @Override public String defaultSeverity() { return "CRITICAL"; }
    @Override public double defaultThreshold() { return 40; }
    @Override public String defaultScope() { return "TYPE"; }
    @Override
    protected String defaultReasonTemplate() {
        return "类型 {type} 加权方法复杂度(WMC) {value} 超过阈值 {threshold}";
    }

    @Override
    protected void collect(FileMetrics file, RuleConfig.RuleSettings settings,
                           List<SmellFinding> sink) {
        for (var type : file.types()) {
            if (type.weightedMethodComplexity() > threshold(settings)) {
                sink.add(typeFinding(file, settings, type,
                        "weightedMethodComplexity",
                        type.weightedMethodComplexity()));
            }
        }
    }
}
