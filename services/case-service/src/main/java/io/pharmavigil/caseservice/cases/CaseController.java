// Read API for safety reviewers: the queue and one case with its latest version, narratives, decisions and
// audit trail. The full reviewer workflow (accept/correct codes, approve, submit) arrives in M8.
package io.pharmavigil.caseservice.cases;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.pharmavigil.caseservice.model.AuditEventRepository;
import io.pharmavigil.caseservice.model.CaseVersion;
import io.pharmavigil.caseservice.model.NarrativeRepository;
import io.pharmavigil.caseservice.model.ReviewDecisionRepository;
import io.pharmavigil.caseservice.model.SafetyCase;
import io.pharmavigil.common.Disclaimers;

@RestController
@RequestMapping("/api/cases")
@PreAuthorize("hasAnyRole('SAFETY_REVIEWER','ADMIN')")
public class CaseController {

    public record QueueItem(Long id, String caseNumber, String status, String seriousness, BigDecimal seriousScore,
            LocalDate receivedDate, LocalDate dueDate, int version, Long duplicateOfId) {
        static QueueItem of(SafetyCase c) {
            return new QueueItem(c.getId(), c.getCaseNumber(), c.getStatus().name(), c.getSeriousness().name(),
                    c.getSeriousScore(), c.getReceivedDate(), c.getDueDate(), c.getCurrentVersion(),
                    c.getDuplicateOfId());
        }
    }

    public record DrugView(String rxcui, String name, String role, String dose, String route) {
    }

    public record ReactionView(String reportedTerm, String pt, String codeSource, String modelName,
            String modelVersion, BigDecimal modelScore) {
    }

    public record CaseDetail(QueueItem head, int lockVersion, String reportedText, BigDecimal patientAge,
            String patientSex, String reporterType, String country, LocalDate onsetDate, List<DrugView> drugs,
            List<ReactionView> reactions, List<?> narratives, List<?> decisions, List<?> audit, String disclaimer) {
    }

    private final CaseService service;
    private final NarrativeRepository narratives;
    private final ReviewDecisionRepository decisions;
    private final AuditEventRepository audit;

    public CaseController(CaseService service, NarrativeRepository narratives, ReviewDecisionRepository decisions,
            AuditEventRepository audit) {
        this.service = service;
        this.narratives = narratives;
        this.decisions = decisions;
        this.audit = audit;
    }

    @GetMapping("/queue")
    public List<QueueItem> queue() {
        return service.queue().stream().map(QueueItem::of).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public CaseDetail get(@PathVariable Long id) {
        SafetyCase c = service.load(id);
        CaseVersion v = service.latest(c);
        return new CaseDetail(QueueItem.of(c), c.getLockVersion(), v.getReportedText(), v.getPatientAge(),
                v.getPatientSex(), v.getReporterType(), v.getCountry(), v.getOnsetDate(),
                v.getDrugs().stream().map(d -> new DrugView(d.getRxcui(), d.getDrugName(), d.getRole(), d.getDoseText(),
                        d.getRoute())).toList(),
                v.getReactions().stream().map(r -> new ReactionView(r.getReportedTerm(), r.getPt(),
                        r.getCodeSource().name(), r.getModelName(), r.getModelVersion(), r.getModelScore())).toList(),
                narratives.findByCaseIdOrderByIdAsc(id), decisions.findByCaseIdOrderByIdAsc(id),
                audit.findByCaseIdOrderByIdAsc(id), Disclaimers.NO_CAUSATION);
    }
}
