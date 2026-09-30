package com.github.guoyyit.codemetricsjava.analysis;

import com.github.guoyyit.codemetricsjava.model.SmellFinding;
import com.github.guoyyit.codemetricsjava.model.Suppression;
import com.github.guoyyit.codemetricsjava.model.SuppressionRequest;
import com.github.guoyyit.codemetricsjava.persistence.SuppressionRepository;
import com.github.guoyyit.codemetricsjava.rule.Fingerprints;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Application service for false-positive suppressions. Suppressions are
 * durable (see {@link SuppressionRepository}) and keyed by a stable finding
 * fingerprint, so they keep applying after nearby line edits.
 */
@Service
public class SuppressionService {

    private final SuppressionRepository repository;

    public SuppressionService(SuppressionRepository repository) {
        this.repository = repository;
    }

    public List<Suppression> list() {
        return repository.loadAll();
    }

    public Suppression grant(SuppressionRequest request) {
        Instant now = Instant.now();
        String id = "SUP-" + Fingerprints.sha256(
                request.ruleId() + "|" + request.fingerprint() + "|" + now.toEpochMilli())
                .substring(0, 12);
        Suppression suppression = new Suppression(
                id,
                request.ruleId(),
                request.fingerprint(),
                request.path(),
                request.reason(),
                request.createdBy() == null ? "anonymous" : request.createdBy(),
                now,
                null,
                null);
        return repository.add(suppression);
    }

    public Optional<Suppression> revoke(String id, String revokedBy) {
        return repository.revoke(id, revokedBy == null ? "anonymous" : revokedBy);
    }

    public SmellFinding apply(SmellFinding finding) {
        Optional<Suppression> match =
                repository.findActive(finding.ruleId(), finding.fingerprint());
        return match.map(suppression -> new SmellFinding(
                finding.ruleId(), finding.severity(), finding.path(),
                finding.location(), finding.metric(), finding.value(),
                finding.threshold(), finding.reason(), true, suppression.id()))
                .orElse(finding);
    }
}
