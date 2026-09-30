package com.github.guoyyit.codemetricsjava.web;

import com.github.guoyyit.codemetricsjava.analysis.AnalyzerService;
import com.github.guoyyit.codemetricsjava.compare.BaselineComparator;
import com.github.guoyyit.codemetricsjava.model.AnalysisReport;
import com.github.guoyyit.codemetricsjava.model.AnalysisRequest;
import com.github.guoyyit.codemetricsjava.model.BaselineComparison;
import com.github.guoyyit.codemetricsjava.model.CompareRequest;
import com.github.guoyyit.codemetricsjava.persistence.ReportRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Read/analyze endpoints: trigger analysis, fetch reports, compare
 * revisions against a baseline.
 */
@RestController
@RequestMapping("/api")
public class ReportController {

    private final AnalyzerService analyzerService;
    private final ReportRepository reportRepository;
    private final BaselineComparator baselineComparator;

    public ReportController(AnalyzerService analyzerService,
                            ReportRepository reportRepository,
                            BaselineComparator baselineComparator) {
        this.analyzerService = analyzerService;
        this.reportRepository = reportRepository;
        this.baselineComparator = baselineComparator;
    }

    @PostMapping("/analyze")
    public AnalysisReport analyze(@RequestBody AnalysisRequest request) {
        return analyzerService.analyze(request);
    }

    @GetMapping("/reports/{revision}")
    public ResponseEntity<AnalysisReport> byRevision(@PathVariable String revision) {
        return reportRepository.findByRevision(revision)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/reports/latest")
    public ResponseEntity<AnalysisReport> latest() {
        return reportRepository.latest()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/reports")
    public List<AnalysisReport> history(@RequestParam(defaultValue = "20") int limit) {
        return reportRepository.history(limit);
    }

    @PostMapping("/compare")
    public ResponseEntity<?> compare(@RequestBody CompareRequest request) {
        var baseline = reportRepository.findByRevision(request.baselineRevision());
        var target = reportRepository.findByRevision(request.targetRevision());
        if (baseline.isEmpty() || target.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "revision not found",
                    "baselinePresent", baseline.isPresent(),
                    "targetPresent", target.isPresent()));
        }
        BaselineComparison comparison =
                baselineComparator.compare(baseline.get(), target.get());
        return ResponseEntity.ok(comparison);
    }
}
