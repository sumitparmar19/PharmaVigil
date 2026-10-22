// Case lifecycle rules in one place:
// - receive: one case per (source, source report id), so a resent report never creates a second case;
// - revise: every content change is a new immutable version, guarded by optimistic locking;
// - status, seriousness, duplicates and narratives change only through audited methods;
// - reviewer decisions keep the model's suggestion next to the final value (feedback for retraining).
package io.pharmavigil.caseservice.cases;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Year;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.pharmavigil.caseservice.model.AuditEvent;
import io.pharmavigil.caseservice.model.AuditEventRepository;
import io.pharmavigil.caseservice.model.CaseEnums.NarrativeSource;
import io.pharmavigil.caseservice.model.CaseEnums.Seriousness;
import io.pharmavigil.caseservice.model.CaseEnums.Source;
import io.pharmavigil.caseservice.model.CaseEnums.Status;
import io.pharmavigil.caseservice.model.CaseVersion;
import io.pharmavigil.caseservice.model.CaseVersionRepository;
import io.pharmavigil.caseservice.model.Narrative;
import io.pharmavigil.caseservice.model.NarrativeRepository;
import io.pharmavigil.caseservice.model.ReviewDecision;
import io.pharmavigil.caseservice.model.ReviewDecisionRepository;
import io.pharmavigil.caseservice.model.SafetyCase;
import io.pharmavigil.caseservice.model.SafetyCaseRepository;
import io.pharmavigil.caseservice.model.VersionDraft;
import io.pharmavigil.common.web.NotFoundException;

@Service
public class CaseService {

    /** A report arriving from the pharmacy, a partner (SOAP) or an E2B(R3) import. */
    public record IncomingReport(Source source, String sourceReportId, LocalDate receivedDate, BigDecimal patientAge,
            String patientSex, String reporterType, String country, LocalDate onsetDate, String reportedText,
            List<VersionDraft.DrugDraft> drugs, List<String> reactionTerms, String seriousCriteria) {
    }

    public record Received(SafetyCase safetyCase, boolean created) {
    }

    private static final List<Status> QUEUE = List.of(Status.TRIAGED, Status.IN_REVIEW, Status.NEEDS_MANUAL_CODING,
            Status.REJECTED);

    private final SafetyCaseRepository cases;
    private final CaseVersionRepository versions;
    private final NarrativeRepository narratives;
    private final AuditEventRepository audit;
    private final ReviewDecisionRepository decisions;
    private final Clock clock;

    public CaseService(SafetyCaseRepository cases, CaseVersionRepository versions, NarrativeRepository narratives,
            AuditEventRepository audit, ReviewDecisionRepository decisions, Clock clock) {
        this.cases = cases;
        this.versions = versions;
        this.narratives = narratives;
        this.audit = audit;
        this.decisions = decisions;
        this.clock = clock;
    }

    @Transactional
    public Received receive(IncomingReport r, String actor) {
        var existing = cases.findBySourceAndSourceReportId(r.source(), r.sourceReportId());
        if (existing.isPresent()) {
            return new Received(existing.get(), false);
        }
        String number = "PV-" + Year.now(clock) + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10)
                .toUpperCase();
        SafetyCase c = cases.save(new SafetyCase(number, r.source(), r.sourceReportId(), r.receivedDate(),
                clock.instant()));
        VersionDraft d = new VersionDraft();
        d.patientAge = r.patientAge();
        d.patientSex = r.patientSex();
        d.reporterType = r.reporterType();
        d.country = r.country();
        d.onsetDate = r.onsetDate();
        d.reportedText = r.reportedText();
        d.seriousCriteria = r.seriousCriteria();
        d.drugs.addAll(r.drugs());
        r.reactionTerms().forEach(t -> d.reactions.add(VersionDraft.ReactionDraft.uncoded(t)));
        writeVersion(c, actor, "received from " + r.source(), d);
        audit(c, actor, "RECEIVED", r.source() + " report " + r.sourceReportId());
        return new Received(c, true);
    }

    /** Create the next version from the latest one; fails with a conflict if the case moved on. */
    @Transactional
    public CaseVersion revise(Long caseId, int expectedLockVersion, String actor, String reason,
            Consumer<VersionDraft> change) {
        SafetyCase c = load(caseId);
        if (c.getLockVersion() != expectedLockVersion) {
            throw new ObjectOptimisticLockingFailureException(SafetyCase.class, caseId);
        }
        VersionDraft d = VersionDraft.from(latest(c));
        change.accept(d);
        CaseVersion v = writeVersion(c, actor, reason, d);
        audit(c, actor, "REVISED", "v" + v.getVersionNo() + ": " + reason);
        return v;
    }

    @Transactional
    public SafetyCase moveTo(Long caseId, Status next, String actor) {
        SafetyCase c = load(caseId);
        Status from = c.getStatus();
        c.moveTo(next, clock.instant());
        audit(c, actor, "STATUS", from + " -> " + next);
        return c;
    }

    @Transactional
    public SafetyCase setSeriousness(Long caseId, Seriousness s, BigDecimal score, String actor) {
        SafetyCase c = load(caseId);
        c.setSeriousness(s, score, clock.instant());
        audit(c, actor, "SERIOUSNESS", s + (score == null ? "" : " score " + score) + "; due " + c.getDueDate());
        return c;
    }

    @Transactional
    public SafetyCase markDuplicate(Long caseId, Long duplicateOf, BigDecimal score, String actor) {
        SafetyCase c = load(caseId);
        c.markDuplicateOf(duplicateOf, score, clock.instant());
        audit(c, actor, "DUPLICATE", duplicateOf == null ? "cleared" : "possible duplicate of case " + duplicateOf);
        return c;
    }

    @Transactional
    public ReviewDecision decide(Long caseId, String reviewer, ReviewDecision.Field field, String itemRef,
            ReviewDecision.Suggestion suggestion, String finalValue) {
        SafetyCase c = load(caseId);
        ReviewDecision d = decisions.save(new ReviewDecision(c.getId(), c.getCurrentVersion(), reviewer, field, itemRef,
                suggestion, finalValue, clock.instant()));
        audit(c, reviewer, "DECISION", field + " " + (d.isAccepted() ? "accepted" : "changed") + ": " + finalValue);
        return d;
    }

    @Transactional
    public Narrative addNarrative(Long caseId, String body, NarrativeSource source, String modelName, String actor) {
        SafetyCase c = load(caseId);
        Narrative n = narratives.save(new Narrative(c.getId(), c.getCurrentVersion(), body, source, modelName, actor,
                clock.instant()));
        audit(c, actor, "NARRATIVE", source + " narrative " + n.getId());
        return n;
    }

    @Transactional
    public Narrative approveNarrative(Long narrativeId, String reviewer) {
        Narrative n = narratives.findById(narrativeId)
                .orElseThrow(() -> new NotFoundException("no narrative " + narrativeId));
        n.approve(reviewer, clock.instant());
        audit(load(n.getCaseId()), reviewer, "NARRATIVE_APPROVED", "narrative " + narrativeId);
        return n;
    }

    /** Reviewer queue: serious first, then unknown, then non-serious; nearest deadline first within each. */
    @Transactional(readOnly = true)
    public List<SafetyCase> queue() {
        return cases.findByStatusIn(QUEUE).stream()
                .sorted(Comparator.comparingInt((SafetyCase c) -> rank(c.getSeriousness()))
                        .thenComparing(SafetyCase::getDueDate).thenComparing(SafetyCase::getId))
                .toList();
    }

    @Transactional(readOnly = true)
    public CaseVersion latest(SafetyCase c) {
        return versions.findByCaseIdAndVersionNo(c.getId(), c.getCurrentVersion())
                .orElseThrow(() -> new IllegalStateException("case " + c.getId() + " has no current version"));
    }

    public SafetyCase load(Long caseId) {
        return cases.findById(caseId).orElseThrow(() -> new NotFoundException("no case " + caseId));
    }

    private static int rank(Seriousness s) {
        return switch (s) {
            case SERIOUS -> 0;
            case UNKNOWN -> 1;
            case NON_SERIOUS -> 2;
        };
    }

    private CaseVersion writeVersion(SafetyCase c, String actor, String reason, VersionDraft d) {
        c.advanceVersion(clock.instant());
        return versions.save(new CaseVersion(c.getId(), c.getCurrentVersion(), actor, clock.instant(), reason, d));
    }

    private void audit(SafetyCase c, String actor, String action, String detail) {
        audit.save(new AuditEvent(c.getId(), actor, action, detail, clock.instant()));
    }
}
