package com.github.guoyyit.codemetricsjava.service;

import com.github.guoyyit.codemetricsjava.model.RuleConfig;
import com.github.guoyyit.codemetricsjava.model.SmellRuleId;
import com.github.guoyyit.codemetricsjava.storage.StorageService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 坏味道规则配置的读取与更新（阈值、开关、作用范围）。
 */
@Service
public class RuleConfigService {

    private final StorageService storage;
    private final Object lock = new Object();

    public RuleConfigService(StorageService storage) {
        this.storage = storage;
    }

    public List<RuleConfig> getConfigs() {
        synchronized (lock) {
            List<RuleConfig> stored = storage.loadRules();
            if (stored != null && !stored.isEmpty()) {
                return stored;
            }
            return defaults();
        }
    }

    public RuleConfig update(String ruleId, RuleConfig patch) {
        SmellRuleId.valueOf(ruleId);
        synchronized (lock) {
            List<RuleConfig> configs = getConfigs();
            RuleConfig existing = configs.stream()
                    .filter(config -> config.ruleId.equals(ruleId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("未知规则: " + ruleId));
            existing.enabled = patch.enabled;
            existing.threshold = patch.threshold;
            if (patch.include != null) {
                existing.include = new ArrayList<>(patch.include);
            }
            if (patch.exclude != null) {
                existing.exclude = new ArrayList<>(patch.exclude);
            }
            storage.saveRules(configs);
            return existing;
        }
    }

    public List<RuleConfig> reset() {
        synchronized (lock) {
            List<RuleConfig> defaults = defaults();
            storage.saveRules(defaults);
            return defaults;
        }
    }

    private List<RuleConfig> defaults() {
        List<RuleConfig> configs = new ArrayList<>();
        for (SmellRuleId rule : SmellRuleId.values()) {
            RuleConfig config = new RuleConfig();
            config.ruleId = rule.name();
            config.enabled = true;
            config.threshold = rule.getDefaultThreshold();
            configs.add(config);
        }
        return configs;
    }
}
