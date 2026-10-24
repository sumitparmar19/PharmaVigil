// Stage 4 (case.triaged): draft a narrative for the reviewer. The model call happens outside any database
// transaction so a slow API never holds a connection; the idempotency check runs before (to skip replays
// cheaply) and again inside the write transaction (so two concurrent deliveries store one draft).
package io.pharmavigil.caseservice.pipeline;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import io.pharmavigil.caseservice.cases.CaseService;
import io.pharmavigil.caseservice.model.CaseVersion;
import io.pharmavigil.caseservice.model.ProcessedEvent;
import io.pharmavigil.caseservice.model.ProcessedEventRepository;
import io.pharmavigil.caseservice.model.SafetyCase;
import io.pharmavigil.caseservice.narrative.CaseFacts;
import io.pharmavigil.caseservice.narrative.NarrativeDrafter;
import io.pharmavigil.common.events.CaseEvent;
import io.pharmavigil.common.kafka.PoisonMessageException;
import io.pharmavigil.common.web.NotFoundException;

@Service
public class NarrativeStage {

    static final String CONSUMER = "case-service.narrative";

    private final ProcessedEventRepository processedRepo;
    private final ProcessedEvents processed;
    private final CaseService cases;
    private final NarrativeDrafter drafter;
    private final TransactionTemplate tx;

    public NarrativeStage(ProcessedEventRepository processedRepo, ProcessedEvents processed, CaseService cases,
            NarrativeDrafter drafter, TransactionTemplate tx) {
        this.processedRepo = processedRepo;
        this.processed = processed;
        this.cases = cases;
        this.drafter = drafter;
        this.tx = tx;
    }

    public void onTriaged(CaseEvent e) {
        if (processedRepo.existsById(new ProcessedEvent.Key(e.eventId(), CONSUMER))) {
            return;
        }
        CaseFacts facts = tx.execute(s -> {
            try {
                SafetyCase c = cases.load(e.caseId());
                CaseVersion v = cases.latest(c);
                return CaseFacts.of(c, v);
            } catch (NotFoundException ex) {
                throw new PoisonMessageException("event " + e.eventId() + " names unknown case " + e.caseId(), ex);
            }
        });
        NarrativeDrafter.Draft draft = drafter.draft(facts);
        tx.executeWithoutResult(s -> {
            if (processed.firstTime(e.eventId(), CONSUMER)) {
                cases.addNarrative(e.caseId(), draft.body(), draft.source(), draft.modelName(), "pipeline");
            }
        });
    }
}
