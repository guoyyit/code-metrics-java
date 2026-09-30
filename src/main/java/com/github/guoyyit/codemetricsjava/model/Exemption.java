package com.github.guoyyit.codemetricsjava.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 坏味道豁免规则。filePattern 为必填 ant glob；ruleId 为空表示适用于全部规则。
 */
public class Exemption {
    public String id;
    public String ruleId;
    public List<String> filePatterns = new ArrayList<>();
    public String typePattern;
    public String methodPattern;
    public String reason;
    public String createdBy;
    public String createdAt;
    public boolean active = true;
    public String updatedAt;
}
