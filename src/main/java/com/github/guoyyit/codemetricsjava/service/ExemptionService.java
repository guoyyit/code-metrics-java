package com.github.guoyyit.codemetricsjava.service;

import com.github.guoyyit.codemetricsjava.model.Exemption;
import com.github.guoyyit.codemetricsjava.model.ExemptionEvent;
import com.github.guoyyit.codemetricsjava.model.FileAnalysisResult;
import com.github.guoyyit.codemetricsjava.model.Finding;
import com.github.guoyyit.codemetricsjava.storage.StorageService;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 豁免的创建、撤销、审计追溯，并负责把生效豁免标注到命中结果上。
 */
@Service
public class ExemptionService {

    private final StorageService storage;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final Object lock = new Object();

    public ExemptionService(StorageService storage) {
        this.storage = storage;
    }

    public List<Exemption> list() {
        List<Exemption> exemptions = storage.loadExemptions();
        exemptions.sort(Comparator.comparing(exemption -> exemption.createdAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return exemptions;
    }

    public Exemption create(Exemption request) {
        if (request.filePatterns == null || request.filePatterns.isEmpty()) {
            throw new IllegalArgumentException("filePatterns 不能为空");
        }
        synchronized (lock) {
            List<Exemption> exemptions = storage.loadExemptions();
            Exemption exemption = new Exemption();
            exemption.id = UUID.randomUUID().toString();
            exemption.ruleId = emptyToNull(request.ruleId);
            exemption.filePatterns = new ArrayList<>(request.filePatterns);
            exemption.typePattern = emptyToNull(request.typePattern);
            exemption.methodPattern = emptyToNull(request.methodPattern);
            exemption.reason = request.reason;
            exemption.createdBy = request.createdBy == null ? "api" : request.createdBy;
            exemption.createdAt = Instant.now().toString();
            exemption.updatedAt = exemption.createdAt;
            exemption.active = true;
            exemptions.add(exemption);
            storage.saveExemptions(exemptions);
            recordEvent(exemption.id, "CREATE", exemption.createdBy, exemption.reason, exemption);
            return exemption;
        }
    }

    public Exemption revoke(String exemptionId, String reason, String operator) {
        synchronized (lock) {
            List<Exemption> exemptions = storage.loadExemptions();
            Exemption exemption = exemptions.stream()
                    .filter(candidate -> candidate.id.equals(exemptionId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("未知豁免: " + exemptionId));
            exemption.active = false;
            exemption.updatedAt = Instant.now().toString();
            storage.saveExemptions(exemptions);
            recordEvent(exemption.id, "REVOKE", operator, reason, exemption);
            return exemption;
        }
    }

    public List<ExemptionEvent> events() {
        List<ExemptionEvent> events = storage.loadExemptionEvents();
        events.sort(Comparator.comparing(event -> event.timestamp,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return events;
    }

    public void applySuppressions(List<FileAnalysisResult> results) {
        List<Exemption> exemptions = storage.loadExemptions().stream()
                .filter(exemption -> exemption.active)
                .toList();
        for (FileAnalysisResult result : results) {
            for (Finding finding : result.findings) {
                finding.suppressed = false;
                finding.exemptionId = null;
                exemptions.stream()
                        .filter(exemption -> matches(exemption, finding))
                        .findFirst()
                        .ifPresent(exemption -> {
                            finding.suppressed = true;
                            finding.exemptionId = exemption.id;
                        });
            }
        }
    }

    private boolean matches(Exemption exemption, Finding finding) {
        if (exemption.ruleId != null && !exemption.ruleId.equals(finding.ruleId)) {
            return false;
        }
        boolean fileMatches = exemption.filePatterns.stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, finding.path));
        if (!fileMatches) {
            return false;
        }
        if (exemption.typePattern != null && !wildcardMatches(exemption.typePattern,
                nullToEmpty(finding.typeQualifiedName))) {
            return false;
        }
        if (exemption.methodPattern != null && !wildcardMatches(exemption.methodPattern,
                nullToEmpty(finding.methodSignature))) {
            return false;
        }
        return true;
    }

    private boolean wildcardMatches(String wildcard, String value) {
        String regex = "(?s)" + Pattern.quote(wildcard)
                .replace("*", "\\E.*\\Q")
                .replace("?", "\\E.\\Q");
        return value.matches(regex);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void recordEvent(String exemptionId, String action, String operator,
                             String reason, Exemption snapshot) {
        ExemptionEvent event = new ExemptionEvent();
        event.eventId = UUID.randomUUID().toString();
        event.exemptionId = exemptionId;
        event.action = action;
        event.operator = operator == null ? "api" : operator;
        event.reason = reason;
        event.timestamp = Instant.now().toString();
        event.snapshot = snapshot;
        storage.appendExemptionEvent(event);
    }
}
