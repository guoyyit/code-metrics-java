package com.github.guoyyit.codemetricsjava.rule;

import com.github.guoyyit.codemetricsjava.model.FileMetrics;
import com.github.guoyyit.codemetricsjava.model.RuleConfig;
import com.github.guoyyit.codemetricsjava.model.SmellFinding;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class RuleEngine {

    private final List<Rule> rules;

    public RuleEngine(List<Rule> rules) {
        this.rules = rules.stream()
                .sorted(Comparator.comparing(Rule::id))
                .toList();
    }

    public List<SmellFinding> evaluate(FileMetrics file, RuleConfig config) {
        List<SmellFinding> findings = new ArrayList<>();
        for (Rule rule : rules) {
            RuleConfig.RuleSettings settings = config.rules().get(rule.id());
            if (settings != null) {
                findings.addAll(rule.evaluate(file, settings));
            }
        }
        findings.sort(Comparator
                .comparing(SmellFinding::path)
                .thenComparing(finding -> finding.location() == null
                        ? 0 : finding.location().startLine())
                .thenComparing(SmellFinding::ruleId)
                .thenComparing(SmellFinding::fingerprint));
        return findings;
    }
}
