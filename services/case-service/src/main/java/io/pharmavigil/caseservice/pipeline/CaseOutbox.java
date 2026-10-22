// Writes pipeline events into case-service's outbox in the caller's transaction (engineering rule 3); the
// common OutboxRelay publishes them to Kafka.
package io.pharmavigil.caseservice.pipeline;

import java.time.Clock;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.pharmavigil.caseservice.model.OutboxEvent;
import io.pharmavigil.caseservice.model.OutboxEventRepository;
import tools.jackson.databind.json.JsonMapper;

@Component
public class CaseOutbox {

    private final OutboxEventRepository outbox;
    private final JsonMapper json;
    private final Clock clock;

    public CaseOutbox(OutboxEventRepository outbox, JsonMapper json, Clock clock) {
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String topic, String eventId, long caseId, Object payload) {
        outbox.save(new OutboxEvent(eventId, "case", Long.toString(caseId), topic, json.writeValueAsString(payload),
                clock.instant()));
    }
}
