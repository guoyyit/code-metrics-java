package com.github.guoyyit.codemetricsjava.analysis;

import com.github.guoyyit.codemetricsjava.config.MemoryLimiter;
import com.github.guoyyit.codemetricsjava.metrics.MetricsCalculator;
import com.github.guoyyit.codemetricsjava.model.FileFailure;
import com.github.guoyyit.codemetricsjava.model.FileMetrics;
import com.github.guoyyit.codemetricsjava.model.RuleConfig;
import com.github.guoyyit.codemetricsjava.model.SourceFile;
import com.github.guoyyit.codemetricsjava.model.SmellFinding;
import com.github.guoyyit.codemetricsjava.parser.JavaSourceParser;
import com.github.guoyyit.codemetricsjava.rule.RuleEngine;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stateless per-file pipeline: acquire memory budget, parse, calculate
 * metrics and evaluate rules. Any failure becomes a {@link FileFailure};
 * nothing escapes as an exception so other files keep processing.
 */
@Component
public class FileAnalysisWorker {

    private final JavaSourceParser parser;
    private final MetricsCalculator metricsCalculator;
    private final RuleEngine ruleEngine;
    private final MemoryLimiter memoryLimiter;

    public FileAnalysisWorker(JavaSourceParser parser,
                              MetricsCalculator metricsCalculator,
                              RuleEngine ruleEngine,
                              MemoryLimiter memoryLimiter) {
        this.parser = parser;
        this.metricsCalculator = metricsCalculator;
        this.ruleEngine = ruleEngine;
        this.memoryLimiter = memoryLimiter;
    }

    public FileResult analyze(SourceFile source, RuleConfig config) {
        long budget = estimate(source);
        memoryLimiter.acquire(budget);
        try {
            JavaSourceParser.ParsedFile parsed = parser.parse(source);
            if (parsed instanceof JavaSourceParser.ParsedFile.Error error) {
                return new FileResult(null, List.of(), error.failure());
            }
            JavaSourceParser.ParsedFile.Success success =
                    (JavaSourceParser.ParsedFile.Success) parsed;
            FileMetrics metrics = metricsCalculator.calculate(source, success.unit());
            List<SmellFinding> findings = ruleEngine.evaluate(metrics, config);
            return new FileResult(metrics, findings, null);
        } catch (RuntimeException ex) {
            return new FileResult(null, List.of(),
                    new FileFailure(source.path(), "ANALYSIS_EXCEPTION",
                            ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        } finally {
            memoryLimiter.release(budget);
        }
    }

    public List<SmellFinding> reevaluate(FileMetrics metrics, RuleConfig config) {
        return ruleEngine.evaluate(metrics, config);
    }

    private long estimate(SourceFile source) {
        return source.sizeBytes() * 4L;
    }

    public record FileResult(FileMetrics metrics, List<SmellFinding> findings,
                             FileFailure failure) {
    }
}
