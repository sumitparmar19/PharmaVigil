// Idempotent-consumer bookkeeping: (event id, consumer) is stored in the same transaction as the work,
// so a redelivered or replayed event is recognized and skipped.
package io.pharmavigil.submission.model;

import java.io.Serializable;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @Embeddable
    public record Key(@Column(name = "event_id", length = 64) String eventId,
            @Column(name = "consumer", length = 64) String consumer) implements Serializable {
    }

    @EmbeddedId
    private Key key;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(String eventId, String consumer, Instant processedAt) {
        this.key = new Key(eventId, consumer);
        this.processedAt = processedAt;
    }

    public Key getKey() { return key; }
    public Instant getProcessedAt() { return processedAt; }
}
