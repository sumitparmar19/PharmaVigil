// Idempotent consumers: the first delivery of an event to a consumer records (event id, consumer) in the same
// transaction as its work; any later delivery (Kafka redelivery, replay from offset 0) is skipped.
package io.pharmavigil.caseservice.pipeline;

import java.time.Clock;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.pharmavigil.caseservice.model.ProcessedEvent;
import io.pharmavigil.caseservice.model.ProcessedEventRepository;

@Component
public class ProcessedEvents {

    private final ProcessedEventRepository repo;
    private final Clock clock;

    public ProcessedEvents(ProcessedEventRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** True the first time; a concurrent duplicate fails on the primary key and rolls back its transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(String eventId, String consumer) {
        if (repo.existsById(new ProcessedEvent.Key(eventId, consumer))) {
            return false;
        }
        repo.saveAndFlush(new ProcessedEvent(eventId, consumer, clock.instant()));
        return true;
    }
}
