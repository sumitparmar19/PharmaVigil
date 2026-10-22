// The case pipeline, one transaction per stage: each stage checks that it has not seen the event before, does
// its work, and writes the next event into the outbox, so work and event commit or roll back together.
//
//   report.received → intake + duplicate check → report.deduped → coding → report.coded → triage → case.triaged
package io.pharmavigil.caseservice.pipeline;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.pharmavigil.caseservice.cases.CaseService;
import io.pharmavigil.caseservice.e2b.ReportIntake;
import io.pharmavigil.caseservice.model.CaseEnums.Status;
import io.pharmavigil.caseservice.model.SafetyCase;
import io.pharmavigil.common.events.CaseEvent;
import io.pharmavigil.common.events.ReportReceived;
import io.pharmavigil.common.events.Topics;
import io.pharmavigil.common.kafka.PoisonMessageException;
import io.pharmavigil.common.web.NotFoundException;

@Service
public class PipelineStages {

    static final String DEDUP = "case-service.dedup";
    static final String CODING = "case-service.coding";
    static final String TRIAGE = "case-service.triage";

    private final ProcessedEvents processed;
    private final ReportIntake intake;
    private final CaseService cases;
    private final DuplicateCheck duplicates;
    private final AutoCoding coding;
    private final Triage triage;
    private final CaseOutbox outbox;
    private final Clock clock;

    public PipelineStages(ProcessedEvents processed, ReportIntake intake, CaseService cases, DuplicateCheck duplicates,
            AutoCoding coding, Triage triage, CaseOutbox outbox, Clock clock) {
        this.processed = processed;
        this.intake = intake;
        this.cases = cases;
        this.duplicates = duplicates;
        this.coding = coding;
        this.triage = triage;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public void onReportReceived(ReportReceived r) {
        if (r.eventId() == null || r.source() == null || r.sourceReportId() == null || r.receivedAt() == null) {
            throw new PoisonMessageException("report.received without eventId, source, sourceReportId or receivedAt",
                    null);
        }
        if (!processed.firstTime(r.eventId(), DEDUP)) {
            return;
        }
        CaseService.Received rec;
        try {
            rec = intake.accept(r);
        } catch (IllegalArgumentException e) { // unknown source enum value
            throw new PoisonMessageException("report.received with unknown source " + r.source(), e);
        }
        if (!rec.created() && !rec.followUp()) {
            return; // identical resend: nothing new to process
        }
        duplicates.check(rec.safetyCase());
        next(Topics.REPORT_DEDUPED, cases.load(rec.safetyCase().getId()));
    }

    @Transactional
    public void onDeduped(CaseEvent e) {
        if (!processed.firstTime(e.eventId(), CODING)) {
            return;
        }
        SafetyCase c = load(e);
        Status target = coding.code(c);
        cases.advance(c.getId(), target, "pipeline");
        next(Topics.REPORT_CODED, cases.load(c.getId()));
    }

    @Transactional
    public void onCoded(CaseEvent e) {
        if (!processed.firstTime(e.eventId(), TRIAGE)) {
            return;
        }
        SafetyCase c = load(e);
        triage.triage(c);
        cases.advance(c.getId(), Status.TRIAGED, "pipeline");
        next(Topics.CASE_TRIAGED, cases.load(c.getId()));
    }

    private SafetyCase load(CaseEvent e) {
        try {
            return cases.load(e.caseId());
        } catch (NotFoundException ex) {
            throw new PoisonMessageException("event " + e.eventId() + " names unknown case " + e.caseId(), ex);
        }
    }

    private void next(String topic, SafetyCase c) {
        String id = UUID.randomUUID().toString();
        outbox.publish(topic, id, c.getId(), new CaseEvent(id, c.getId(), c.getCaseNumber(), c.getCurrentVersion(),
                c.getStatus().name(), clock.instant()));
    }
}
