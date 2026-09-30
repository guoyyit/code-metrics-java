package com.github.guoyyit.codemetricsjava.rule;

import com.github.guoyyit.codemetricsjava.model.FileMetrics;
import com.github.guoyyit.codemetricsjava.model.RuleConfig;
import com.github.guoyyit.codemetricsjava.model.SmellFinding;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class HighFanOutRule extends AbstractRule {
    @Override public String id() { return "HIGH_FAN_OUT"; }
    @Override public String defaultSeverity() { return "MAJOR"; }
    @Override public double defaultThreshold() { return 10; }
    @Override public String defaultScope() { return "TYPE"; }
    @Override
    protected String defaultReasonTemplate() {
        return "类型 {type} 扇出(fan-out) {value} 超过阈值 {threshold}";
    }

    @Override
    protected void collect(FileMetrics file, RuleConfig.RuleSettings settings,
                           List<SmellFinding> sink) {
        for (var type : file.types()) {
            if (type.fanOut() > threshold(settings)) {
                sink.add(typeFinding(file, settings, type,
                        "fanOut", type.fanOut()));
            }
        }
    }
}
