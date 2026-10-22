// Who did what to a case, and when. Written for every state change; never updated or deleted.
package io.pharmavigil.caseservice.model;

import java.time.Instant;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Immutable
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(nullable = false, length = 64)
    private String actor;

    @Column(nullable = false, length = 48)
    private String action;

    @Column(length = 2000)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditEvent() {
    }

    public AuditEvent(Long caseId, String actor, String action, String detail, Instant occurredAt) {
        this.caseId = caseId;
        this.actor = actor;
        this.action = action;
        this.detail = detail == null || detail.length() <= 2000 ? detail : detail.substring(0, 2000);
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public Long getCaseId() { return caseId; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
}
