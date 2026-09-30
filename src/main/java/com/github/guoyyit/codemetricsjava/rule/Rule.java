package com.github.guoyyit.codemetricsjava.rule;

import com.github.guoyyit.codemetricsjava.model.FileMetrics;
import com.github.guoyyit.codemetricsjava.model.RuleConfig;
import com.github.guoyyit.codemetricsjava.model.SmellFinding;

import java.util.List;

public interface Rule {

    String id();

    String defaultSeverity();

    double defaultThreshold();

    String defaultScope();

    List<SmellFinding> evaluate(FileMetrics file, RuleConfig.RuleSettings settings);
}
