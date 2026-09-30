package com.github.guoyyit.codemetricsjava.model;

/**
 * 豁免变更审计事件，保证豁免变更全程可追溯。
 */
public class ExemptionEvent {
    public String eventId;
    public String exemptionId;
    public String action;
    public String operator;
    public String reason;
    public String timestamp;
    public Exemption snapshot;
}
