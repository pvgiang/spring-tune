package io.github.winkytek.springtune.boot;

import org.springframework.context.ApplicationEvent;

/**
 * Published for every audited apply-mode action: when a change is applied, when its outcome becomes final,
 * and for refusals and freezes. Listen to it to forward changes to a chat channel or an external audit log.
 */
public class SpringTuneChangeEvent extends ApplicationEvent {

    private final transient ChangeAuditEntry entry;

    public SpringTuneChangeEvent(Object source, ChangeAuditEntry entry) {
        super(source);
        this.entry = entry;
    }

    public ChangeAuditEntry getEntry() {
        return entry;
    }
}
