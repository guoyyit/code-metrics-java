package com.github.guoyyit.codemetricsjava.config;

import com.github.guoyyit.codemetricsjava.model.RuleConfig;
import com.github.guoyyit.codemetricsjava.rule.Fingerprints;
import com.github.guoyyit.codemetricsjava.rule.Rule;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads, persists and versions rule configurations. Every accepted update
 * is stored atomically and archived under {@code config-history/}, so the
 * effective config for any past analysis remains traceable.
 */
@Service
public class RuleConfigService {

    private final MetricsProperties properties;
    private final List<Rule> rules;

    private volatile RuleConfig current;

    public RuleConfigService(MetricsProperties properties, List<Rule> rules) {
        this.properties = properties;
        this.rules = rules;
    }

    @PostConstruct
    public void init() {
        Path configFile = configFile();
        if (Files.exists(configFile)) {
            this.current = JsonSupport.read(configFile, RuleConfig.class);
        } else {
            this.current = defaultConfig();
            persist(this.current);
        }
    }

    public RuleConfig current() {
        return current;
    }

    public synchronized RuleConfig update(RuleConfig config) {
        RuleConfig normalized = normalize(config);
        persist(normalized);
        archive(normalized);
        this.current = normalized;
        return normalized;
    }

    public String configHash() {
        try {
            String json = JsonSupport.mapper().writeValueAsString(current);
            return Fingerprints.sha256(json);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private RuleConfig defaultConfig() {
        Map<String, RuleConfig.RuleSettings> settings = new LinkedHashMap<>();
        for (Rule rule : rules.stream().sorted(java.util.Comparator.comparing(Rule::id)).toList()) {
            settings.put(rule.id(), new RuleConfig.RuleSettings(
                    true,
                    rule.defaultSeverity(),
                    rule.defaultScope(),
                    rule.defaultThreshold(),
                    List.of(),
                    null));
        }
        return new RuleConfig("1", settings);
    }

    private RuleConfig normalize(RuleConfig incoming) {
        Map<String, RuleConfig.RuleSettings> merged = new LinkedHashMap<>();
        for (Rule rule : rules.stream().sorted(java.util.Comparator.comparing(Rule::id)).toList()) {
            RuleConfig.RuleSettings provided = incoming.rules() == null
                    ? null : incoming.rules().get(rule.id());
            if (provided == null) {
                merged.put(rule.id(), new RuleConfig.RuleSettings(
                        true, rule.defaultSeverity(), rule.defaultScope(),
                        rule.defaultThreshold(), List.of(), null));
            } else {
                merged.put(rule.id(), new RuleConfig.RuleSettings(
                        provided.enabled(),
                        provided.severity() == null ? rule.defaultSeverity() : provided.severity(),
                        provided.scope() == null ? rule.defaultScope() : provided.scope(),
                        provided.threshold(),
                        provided.pathPatterns() == null ? List.of()
                                : List.copyOf(provided.pathPatterns()),
                        provided.reasonTemplate()));
            }
        }
        String version = incoming.version() == null ? "1" : incoming.version();
        return new RuleConfig(version, merged);
    }

    private void persist(RuleConfig config) {
        JsonSupport.writeAtomically(configFile(), config);
    }

    private void archive(RuleConfig config) {
        Path history = storageDir().resolve("config-history")
                .resolve(config.version() + "-" + Instant.now().toEpochMilli() + ".json");
        JsonSupport.writeAtomically(history, config);
        try {
            Files.copy(configFile(),
                    storageDir().resolve("config-history").resolve("latest.json"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Path configFile() {
        return storageDir().resolve("rules.json");
    }

    private Path storageDir() {
        return Path.of(properties.getStorageDir());
    }
}
