package com.github.guoyyit.codemetricsjava.persistence;

import com.github.guoyyit.codemetricsjava.config.JsonSupport;
import com.github.guoyyit.codemetricsjava.config.MetricsProperties;
import com.github.guoyyit.codemetricsjava.model.Suppression;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Persistent store for suppressions. The authoritative state file is
 * rewritten atomically on every change; an append-only JSON-lines audit log
 * records every grant and revocation to keep the history traceable.
 */
@Component
public class SuppressionRepository {

    private final MetricsProperties properties;
    private final List<Suppression> suppressions = new ArrayList<>();

    public SuppressionRepository(MetricsProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public synchronized void init() {
        Path file = stateFile();
        suppressions.clear();
        if (Files.exists(file)) {
            Suppression[] loaded = JsonSupport.read(file, Suppression[].class);
            suppressions.addAll(List.of(loaded));
        }
    }

    public synchronized List<Suppression> loadAll() {
        return List.copyOf(suppressions);
    }

    public synchronized Suppression add(Suppression suppression) {
        suppressions.add(suppression);
        suppressions.sort(Comparator
                .comparing(Suppression::createdAt)
                .thenComparing(Suppression::id));
        persist();
        audit(new AuditEntry("GRANT", suppression.id(), suppression.ruleId(),
                suppression.fingerprint(), suppression.path(),
                suppression.createdBy(), suppression.createdAt()));
        return suppression;
    }

    public synchronized Optional<Suppression> findActive(String ruleId, String fingerprint) {
        Instant now = Instant.now();
        return suppressions.stream()
                .filter(item -> item.ruleId().equals(ruleId)
                        && item.fingerprint().equals(fingerprint)
                        && item.activeAt(now))
                .max(Comparator.comparing(Suppression::createdAt));
    }

    public synchronized Optional<Suppression> revoke(String id, String revokedBy) {
        for (int i = 0; i < suppressions.size(); i++) {
            Suppression item = suppressions.get(i);
            if (item.id().equals(id) && item.revokedAt() == null) {
                Instant revokedAt = Instant.now();
                Suppression revoked = new Suppression(item.id(), item.ruleId(),
                        item.fingerprint(), item.path(), item.reason(),
                        item.createdBy(), item.createdAt(), revokedAt, revokedBy);
                suppressions.set(i, revoked);
                persist();
                audit(new AuditEntry("REVOKE", revoked.id(), revoked.ruleId(),
                        revoked.fingerprint(), revoked.path(),
                        revokedBy, revokedAt));
                return Optional.of(revoked);
            }
        }
        return Optional.empty();
    }

    private void persist() {
        JsonSupport.writeAtomically(stateFile(), suppressions);
    }

    private void audit(AuditEntry entry) {
        try {
            Files.createDirectories(logFile().getParent());
            Files.writeString(logFile(),
                    compactMapper().writeValueAsString(entry) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Path stateFile() {
        return Path.of(properties.getStorageDir()).resolve("suppressions.json");
    }

    private Path logFile() {
        return Path.of(properties.getStorageDir()).resolve("audit").resolve("suppressions.log");
    }

    private record AuditEntry(
            String action,
            String suppressionId,
            String ruleId,
            String fingerprint,
            String path,
            String operator,
            Instant at
    ) {
    }

    private static ObjectMapper compactMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
