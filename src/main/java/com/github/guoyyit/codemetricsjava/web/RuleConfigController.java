package com.github.guoyyit.codemetricsjava.web;

import com.github.guoyyit.codemetricsjava.config.RuleConfigService;
import com.github.guoyyit.codemetricsjava.model.RuleConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Rule configuration endpoints. Thresholds, enable flags, severities and
 * path scopes are adjustable at runtime; each update is archived.
 */
@RestController
@RequestMapping("/api/rules")
public class RuleConfigController {

    private final RuleConfigService ruleConfigService;

    public RuleConfigController(RuleConfigService ruleConfigService) {
        this.ruleConfigService = ruleConfigService;
    }

    @GetMapping
    public RuleConfig current() {
        return ruleConfigService.current();
    }

    @PutMapping
    public RuleConfig update(@RequestBody RuleConfig config) {
        return ruleConfigService.update(config);
    }

    @GetMapping("/hash")
    public Map<String, String> hash() {
        return Map.of("configHash", ruleConfigService.configHash());
    }
}
