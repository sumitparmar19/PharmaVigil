// Turns a report.received event (pharmacy report, partner SOAP message or imported E2B(R3) file) into a case:
// a new case, a new version of an existing one (follow-up), or nothing for an identical resend. The Kafka
// consumer (M8) calls this after its processed-event check.
package io.pharmavigil.caseservice.e2b;

import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Component;

import io.pharmavigil.caseservice.cases.CaseService;
import io.pharmavigil.caseservice.model.CaseEnums.Source;
import io.pharmavigil.caseservice.model.VersionDraft;
import io.pharmavigil.common.events.ReportReceived;

@Component
public class ReportIntake {

    static final String ACTOR = "system:intake";

    private final CaseService cases;

    public ReportIntake(CaseService cases) {
        this.cases = cases;
    }

    public CaseService.Received accept(ReportReceived r) {
        List<VersionDraft.DrugDraft> drugs = r.drugs() == null ? List.of() : r.drugs().stream()
                .map(d -> new VersionDraft.DrugDraft(d.rxcui(), d.name(), d.role(), d.doseText(), d.route()))
                .toList();
        return cases.receive(new CaseService.IncomingReport(Source.valueOf(r.source()), r.sourceReportId(),
                r.receivedAt().atOffset(ZoneOffset.UTC).toLocalDate(), r.patientAgeYears(), r.patientSex(),
                r.reporterType(), r.country(), r.onsetDate(), r.narrative(), drugs,
                r.reactionTerms() == null ? List.of() : r.reactionTerms(), r.seriousCriteria()), ACTOR);
    }
}
