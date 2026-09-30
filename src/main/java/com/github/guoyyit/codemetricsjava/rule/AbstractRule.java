package com.github.guoyyit.codemetricsjava.rule;

import com.github.guoyyit.codemetricsjava.model.FileMetrics;
import com.github.guoyyit.codemetricsjava.model.MethodMetrics;
import com.github.guoyyit.codemetricsjava.model.RuleConfig;
import com.github.guoyyit.codemetricsjava.model.SmellFinding;
import com.github.guoyyit.codemetricsjava.model.SourceLocation;
import com.github.guoyyit.codemetricsjava.model.TypeMetrics;

import java.util.ArrayList;
import java.util.List;

/**
 * Common scaffolding for built-in rules: threshold comparison, reason
 * rendering and scope filtering are handled uniformly here. A finding is
 * emitted when the measured value is strictly greater than the threshold,
 * so a threshold of N means "up to N inclusive is acceptable".
 */
public abstract class AbstractRule implements Rule {

    @Override
    public final List<SmellFinding> evaluate(FileMetrics file, RuleConfig.RuleSettings settings) {
        if (!settings.enabled() || !inScope(file.path(), settings)) {
            return List.of();
        }
        List<SmellFinding> findings = new ArrayList<>();
        collect(file, settings, findings);
        return findings;
    }

    protected abstract void collect(FileMetrics file, RuleConfig.RuleSettings settings,
                                    List<SmellFinding> sink);

    protected boolean inScope(String path, RuleConfig.RuleSettings settings) {
        List<String> patterns = settings.pathPatterns();
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }
        String normalized = path.replace('\\', '/');
        for (String pattern : patterns) {
            if (matches(normalized, pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(String path, String glob) {
        String regex = glob.replace(".", "\\.")
                .replace("**/", "::DOUBLEDIR::")
                .replace("**", "::DOUBLE::")
                .replace("*", "[^/]*")
                .replace("::DOUBLEDIR::", "(.*/)?")
                .replace("::DOUBLE::", ".*");
        return path.matches("(?s).*" + regex) || path.matches(regex);
    }

    protected SmellFinding methodFinding(FileMetrics file, RuleConfig.RuleSettings settings,
                                         TypeMetrics type, MethodMetrics method,
                                         String metric, double value) {
        return new SmellFinding(id(), settings.severity(), file.path(),
                method.location(), metric, value, settings.threshold(),
                reason(settings, type.qualifiedName(), method.name(), value),
                false, null);
    }

    protected SmellFinding typeFinding(FileMetrics file, RuleConfig.RuleSettings settings,
                                       TypeMetrics type, String metric, double value) {
        SourceLocation location = new SourceLocation(file.path(),
                type.qualifiedName(), null,
                type.location().startLine(), type.location().endLine());
        return new SmellFinding(id(), settings.severity(), file.path(),
                location, metric, value, settings.threshold(),
                reason(settings, type.qualifiedName(), null, value),
                false, null);
    }

    private String reason(RuleConfig.RuleSettings settings, String typeName,
                          String methodName, double value) {
        String template = settings.reasonTemplate();
        if (template == null || template.isBlank()) {
            template = defaultReasonTemplate();
        }
        return template.replace("{rule}", id())
                .replace("{type}", typeName)
                .replace("{method}", methodName == null ? "" : methodName)
                .replace("{value}", format(value))
                .replace("{threshold}", format(settings.threshold()));
    }

    protected String defaultReasonTemplate() {
        return "{rule}: {type}{method} measured {value} > threshold {threshold}";
    }

    private String format(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    protected double threshold(RuleConfig.RuleSettings settings) {
        return settings.threshold();
    }
}
