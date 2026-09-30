package com.github.guoyyit.codemetricsjava.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条坏味道规则的配置：开关、阈值与作用范围（ant 风格 glob，include 为空表示全部）。
 */
public class RuleConfig {
    public String ruleId;
    public boolean enabled = true;
    public double threshold;
    public List<String> include = new ArrayList<>();
    public List<String> exclude = new ArrayList<>();
}
