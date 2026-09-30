package com.github.guoyyit.codemetricsjava.web;

import com.github.guoyyit.codemetricsjava.model.AnalysisRequest;
import com.github.guoyyit.codemetricsjava.model.AnalysisSnapshot;
import com.github.guoyyit.codemetricsjava.model.ComparisonResult;
import com.github.guoyyit.codemetricsjava.model.Exemption;
import com.github.guoyyit.codemetricsjava.model.ExemptionEvent;
import com.github.guoyyit.codemetricsjava.model.FileAnalysisResult;
import com.github.guoyyit.codemetricsjava.model.Finding;
import com.github.guoyyit.codemetricsjava.model.RuleConfig;
import com.github.guoyyit.codemetricsjava.model.SnapshotSummary;
import com.github.guoyyit.codemetricsjava.service.AnalysisService;
import com.github.guoyyit.codemetricsjava.service.ComparisonService;
import com.github.guoyyit.codemetricsjava.service.ExemptionService;
import com.github.guoyyit.codemetricsjava.service.RuleConfigService;
import com.github.guoyyit.codemetricsjava.storage.StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 结构度量与坏味道检测的服务接口。
 */
@RestController
@RequestMapping("/api")
public class MetricsController {

    private final AnalysisService analysisService;
    private final ComparisonService comparisonService;
    private final RuleConfigService ruleConfigService;
    private final ExemptionService exemptionService;
    private final StorageService storage;

    public MetricsController(AnalysisService analysisService,
                             ComparisonService comparisonService,
                             RuleConfigService ruleConfigService,
                             ExemptionService exemptionService,
                             StorageService storage) {
        this.analysisService = analysisService;
        this.comparisonService = comparisonService;
        this.ruleConfigService = ruleConfigService;
        this.exemptionService = exemptionService;
        this.storage = storage;
    }

    @PostMapping("/analyses")
    public AnalysisSnapshot analyze(@RequestBody(required = false) AnalysisRequest request) {
        return analysisService.analyze(request == null ? new AnalysisRequest() : request);
    }

    @GetMapping("/snapshots")
    public List<SnapshotSummary> listSnapshots() {
        return storage.listSnapshots();
    }

    @GetMapping("/snapshots/{id}")
    public AnalysisSnapshot getSnapshot(@PathVariable String id) {
        AnalysisSnapshot snapshot = storage.loadSnapshot(id);
        if (snapshot == null) {
            throw new IllegalArgumentException("快照不存在: " + id);
        }
        return snapshot;
    }

    @GetMapping("/snapshots/{id}/findings")
    public List<Finding> listFindings(@PathVariable String id,
                                      @RequestParam(required = false) String ruleId,
                                      @RequestParam(required = false) Boolean suppressed) {
        AnalysisSnapshot snapshot = getSnapshot(id);
        List<Finding> findings = new ArrayList<>();
        for (FileAnalysisResult result : snapshot.results) {
            for (Finding finding : result.findings) {
                if (ruleId != null && !ruleId.equals(finding.ruleId)) {
                    continue;
                }
                if (suppressed != null && suppressed != finding.suppressed) {
                    continue;
                }
                findings.add(finding);
            }
        }
        findings.sort(java.util.Comparator
                .comparing((Finding finding) -> finding.path)
                .thenComparingInt(finding -> finding.beginLine)
                .thenComparing(finding -> finding.ruleId));
        return findings;
    }

    @GetMapping("/comparisons")
    public ComparisonResult compare(@RequestParam String base, @RequestParam String target) {
        return comparisonService.compare(base, target);
    }

    @GetMapping("/rules")
    public List<RuleConfig> listRules() {
        return ruleConfigService.getConfigs();
    }

    @PutMapping("/rules/{ruleId}")
    public RuleConfig updateRule(@PathVariable String ruleId, @RequestBody RuleConfig patch) {
        return ruleConfigService.update(ruleId, patch);
    }

    @PostMapping("/rules/reset")
    public List<RuleConfig> resetRules() {
        return ruleConfigService.reset();
    }

    @GetMapping("/exemptions")
    public List<Exemption> listExemptions() {
        return exemptionService.list();
    }

    @PostMapping("/exemptions")
    public Exemption createExemption(@RequestBody Exemption request) {
        return exemptionService.create(request);
    }

    @DeleteMapping("/exemptions/{id}")
    public Exemption revokeExemption(@PathVariable String id,
                                     @RequestParam(defaultValue = "revoked") String reason,
                                     @RequestParam(defaultValue = "api") String operator) {
        return exemptionService.revoke(id, reason, operator);
    }

    @GetMapping("/exemptions/events")
    public List<ExemptionEvent> exemptionEvents() {
        return exemptionService.events();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException exception) {
        return Map.of("error", exception.getMessage());
    }
}
