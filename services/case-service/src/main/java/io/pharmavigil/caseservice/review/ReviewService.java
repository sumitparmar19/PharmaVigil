// The safety reviewer's workflow. Every AI suggestion (PT code, seriousness, duplicate flag, narrative) is
// confirmed or corrected here, and each decision is stored with the model's suggestion next to the final value
// (engineering rule 5). A case can be approved only when every reaction has a code, seriousness is decided and
// a narrative is approved; it can be submitted only when its E2B(R3) export passes the official schemas
// (engineering rule 7). Submission results (QUEUED, ACK, NACK) come back from the submission gateway.
package io.pharmavigil.caseservice.review;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.pharmavigil.caseservice.cases.CaseService;
import io.pharmavigil.caseservice.e2b.E2bExportService;
import io.pharmavigil.caseservice.model.CaseEnums.CodeSource;
import io.pharmavigil.caseservice.model.CaseEnums.NarrativeSource;
import io.pharmavigil.caseservice.model.CaseEnums.Seriousness;
import io.pharmavigil.caseservice.model.CaseEnums.Status;
import io.pharmavigil.caseservice.model.CaseReaction;
import io.pharmavigil.caseservice.model.CaseVersion;
import io.pharmavigil.caseservice.model.Narrative;
import io.pharmavigil.caseservice.model.NarrativeRepository;
import io.pharmavigil.caseservice.model.ReviewDecision;
import io.pharmavigil.caseservice.model.SafetyCase;
import io.pharmavigil.caseservice.model.SubmissionEvent;
import io.pharmavigil.caseservice.model.SubmissionEventRepository;
import io.pharmavigil.caseservice.model.VersionDraft;
import io.pharmavigil.caseservice.pipeline.CaseOutbox;
import io.pharmavigil.caseservice.pipeline.ProcessedEvents;
import io.pharmavigil.common.events.CaseReady;
import io.pharmavigil.common.events.SubmissionStatus;
import io.pharmavigil.common.events.Topics;
import io.pharmavigil.common.web.ConflictException;
import io.pharmavigil.common.web.NotFoundException;

@Service
public class ReviewService {

    /** The export failed the official schema check (or the schemas are not installed): nothing was queued. */
    public static class SubmissionBlockedException extends RuntimeException {
        private final List<String> errors;

        public SubmissionBlockedException(String message, List<String> errors) {
            super(message);
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }

    static final String SUBMISSION_CONSUMER = "case-service.submission-status";
    private static final Set<Status> REVIEWABLE = EnumSet.of(Status.TRIAGED, Status.NEEDS_MANUAL_CODING,
            Status.IN_REVIEW);
    private static final Set<String> OPEN_SUBMISSION = Set.of("REQUESTED", "QUEUED", "ACK");

    private final CaseService cases;
    private final NarrativeRepository narratives;
    private final SubmissionEventRepository submissions;
    private final E2bExportService exports;
    private final CaseOutbox outbox;
    private final ProcessedEvents processed;
    private final Clock clock;

    public ReviewService(CaseService cases, NarrativeRepository narratives, SubmissionEventRepository submissions,
            E2bExportService exports, CaseOutbox outbox, ProcessedEvents processed, Clock clock) {
        this.cases = cases;
        this.narratives = narratives;
        this.submissions = submissions;
        this.exports = exports;
        this.outbox = outbox;
        this.processed = processed;
        this.clock = clock;
    }

    /** The first reviewer action takes the case from the queue into review. */
    private SafetyCase inReview(Long caseId, String reviewer) {
        SafetyCase c = cases.load(caseId);
        if (!REVIEWABLE.contains(c.getStatus())) {
            throw new ConflictException("Case " + c.getCaseNumber() + " is " + c.getStatus()
                    + "; it can be reviewed only while it is in the review queue.");
        }
        if (c.getStatus() != Status.IN_REVIEW) {
            cases.advance(caseId, Status.IN_REVIEW, reviewer);
        }
        return c;
    }

    /** Accept (same PT) or correct (another PT) the code of one reaction of the current version. */
    @Transactional
    public CaseVersion codeReaction(Long caseId, int index, String pt, int expectedLockVersion, String reviewer) {
        if (pt == null || pt.isBlank()) {
            throw new IllegalArgumentException("pt is required");
        }
        SafetyCase c = inReview(caseId, reviewer);
        List<CaseReaction> reactions = cases.latest(c).getReactions();
        if (index < 0 || index >= reactions.size()) {
            throw new NotFoundException("case " + c.getCaseNumber() + " has no reaction " + index);
        }
        CaseReaction r = reactions.get(index);
        ReviewDecision.Suggestion suggestion = r.getCodeSource() == CodeSource.MODEL
                ? new ReviewDecision.Suggestion(r.getPt(), r.getModelName(), r.getModelVersion(), r.getModelScore())
                : null;
        String finalPt = pt.strip();
        CaseVersion v = cases.revise(caseId, expectedLockVersion, reviewer, "reviewer coded reaction " + index,
                d -> d.reactions.set(index, new VersionDraft.ReactionDraft(r.getReportedTerm(), finalPt,
                        CodeSource.REVIEWER, null, null, null)));
        cases.decide(caseId, reviewer, ReviewDecision.Field.PT, r.getReportedTerm(), suggestion, finalPt);
        return v;
    }

    @Transactional
    public SafetyCase decideSeriousness(Long caseId, Seriousness value, String reviewer) {
        if (value == null || value == Seriousness.UNKNOWN) {
            throw new IllegalArgumentException("seriousness must be SERIOUS or NON_SERIOUS");
        }
        SafetyCase c = inReview(caseId, reviewer);
        ReviewDecision.Suggestion suggestion = c.getSeriousScore() == null ? null
                : new ReviewDecision.Suggestion(c.getSeriousness().name(), "seriousness-lightgbm", null,
                        c.getSeriousScore());
        cases.setSeriousness(caseId, value, c.getSeriousScore(), reviewer);
        cases.decide(caseId, reviewer, ReviewDecision.Field.SERIOUSNESS, null, suggestion, value.name());
        return cases.load(caseId);
    }

    /** duplicateOf null: not a duplicate; otherwise confirm or set the case it duplicates. */
    @Transactional
    public SafetyCase decideDuplicate(Long caseId, Long duplicateOf, String reviewer) {
        SafetyCase c = inReview(caseId, reviewer);
        if (duplicateOf != null) {
            if (duplicateOf.equals(caseId)) {
                throw new IllegalArgumentException("a case cannot duplicate itself");
            }
            cases.load(duplicateOf);
        }
        ReviewDecision.Suggestion suggestion = c.getDuplicateOfId() == null && c.getDuplicateScore() == null ? null
                : new ReviewDecision.Suggestion(c.getDuplicateOfId() == null ? null : c.getDuplicateOfId().toString(),
                        "duplicates-lightgbm", null, c.getDuplicateScore());
        cases.markDuplicate(caseId, duplicateOf, c.getDuplicateScore(), reviewer);
        cases.decide(caseId, reviewer, ReviewDecision.Field.DUPLICATE, null, suggestion,
                duplicateOf == null ? null : duplicateOf.toString());
        return cases.load(caseId);
    }

    /** A reviewer-written narrative (an edited draft): a new row, never an update. */
    @Transactional
    public Narrative writeNarrative(Long caseId, String body, String reviewer) {
        if (body == null || body.isBlank() || body.length() > 4000) {
            throw new IllegalArgumentException("narrative must be 1 to 4000 characters");
        }
        inReview(caseId, reviewer);
        return cases.addNarrative(caseId, body.strip(), NarrativeSource.REVIEWER, null, reviewer);
    }

    @Transactional
    public Narrative approveNarrative(Long caseId, Long narrativeId, String reviewer) {
        inReview(caseId, reviewer);
        Narrative n = narratives.findById(narrativeId)
                .filter(x -> x.getCaseId().equals(caseId))
                .orElseThrow(() -> new NotFoundException("no narrative " + narrativeId + " on this case"));
        if (n.getApprovedAt() != null) {
            throw new ConflictException("Narrative " + narrativeId + " is already approved.");
        }
        Narrative draft = narratives.findByCaseIdOrderByIdAsc(caseId).stream()
                .filter(x -> x.getSource() != NarrativeSource.REVIEWER).reduce((a, b) -> b).orElse(null);
        ReviewDecision.Suggestion suggestion = draft == null ? null
                : new ReviewDecision.Suggestion("narrative " + draft.getId(), draft.getModelName(), null, null);
        Narrative approved = cases.approveNarrative(narrativeId, reviewer);
        cases.decide(caseId, reviewer, ReviewDecision.Field.NARRATIVE, null, suggestion, "narrative " + narrativeId);
        return approved;
    }

    @Transactional
    public SafetyCase approve(Long caseId, String reviewer) {
        SafetyCase c = cases.load(caseId);
        if (c.getStatus() != Status.IN_REVIEW) {
            throw new ConflictException("Only a case in review can be approved; " + c.getCaseNumber() + " is "
                    + c.getStatus() + ".");
        }
        CaseVersion v = cases.latest(c);
        if (v.getReactions().isEmpty() || v.getReactions().stream().anyMatch(r -> r.getPt() == null)) {
            throw new ConflictException("Every reaction needs a code before approval.");
        }
        if (v.getReactions().stream().anyMatch(r -> r.getCodeSource() == CodeSource.MODEL)) {
            throw new ConflictException("Model codes are suggestions: accept or correct each one before approval.");
        }
        if (c.getSeriousness() == Seriousness.UNKNOWN) {
            throw new ConflictException("Decide seriousness before approval.");
        }
        boolean narrativeApproved = narratives.findByCaseIdOrderByIdAsc(caseId).stream()
                .anyMatch(n -> n.getApprovedAt() != null);
        if (!narrativeApproved) {
            throw new ConflictException("Approve a narrative before approving the case.");
        }
        cases.moveTo(caseId, Status.APPROVED, reviewer);
        return cases.load(caseId);
    }

    /** Export, check against the official schemas, and queue for submission through the outbox. */
    @Transactional
    public SubmissionEvent submit(Long caseId, String reviewer) {
        SafetyCase c = cases.load(caseId);
        if (c.getStatus() != Status.APPROVED) {
            throw new ConflictException("Only an approved case can be submitted; " + c.getCaseNumber() + " is "
                    + c.getStatus() + ".");
        }
        List<SubmissionEvent> history = submissions.findByCaseIdOrderByIdAsc(caseId);
        if (!history.isEmpty() && history.get(history.size() - 1).getVersionNo() == c.getCurrentVersion()
                && OPEN_SUBMISSION.contains(history.get(history.size() - 1).getStatus())) {
            throw new ConflictException("Version " + c.getCurrentVersion() + " is already submitted.");
        }
        E2bExportService.Export export = exports.export(caseId);
        if (!export.queueable()) {
            throw new SubmissionBlockedException("The E2B(R3) file did not pass the official schema check ("
                    + export.validation().outcome() + "); nothing was queued.", export.validation().errors());
        }
        String eventId = UUID.randomUUID().toString();
        outbox.publish(Topics.CASE_READY, eventId, caseId, new CaseReady(eventId, caseId, c.getCaseNumber(),
                c.getCurrentVersion(), export.safetyReportId(), export.xml(), clock.instant()));
        cases.note(caseId, reviewer, "SUBMISSION", "queued " + export.safetyReportId() + " for submission");
        return submissions.save(new SubmissionEvent(caseId, c.getCurrentVersion(), export.safetyReportId(), null,
                "REQUESTED", null, clock.instant()));
    }

    /** submission.status from the gateway: record it and move the case (a NACK sends it back to review). */
    @Transactional
    public void onSubmissionStatus(SubmissionStatus s) {
        if (!processed.firstTime(s.eventId(), SUBMISSION_CONSUMER)) {
            return;
        }
        SafetyCase c = cases.load(s.caseId());
        submissions.save(new SubmissionEvent(c.getId(), c.getCurrentVersion(), s.safetyReportId(), s.messageId(),
                s.status().name(), s.reason(), clock.instant()));
        String actor = "submission-gateway";
        switch (s.status()) {
            case QUEUED -> cases.advance(c.getId(), Status.SUBMITTED, actor);
            case ACK -> cases.advance(c.getId(), Status.ACKNOWLEDGED, actor);
            case NACK -> {
                cases.advance(c.getId(), Status.SUBMITTED, actor); // a NACK can overtake QUEUED
                cases.advance(c.getId(), Status.REJECTED, actor);
                cases.advance(c.getId(), Status.IN_REVIEW, actor);
                cases.note(c.getId(), actor, "NACK", s.reason() == null ? "rejected" : s.reason());
            }
        }
    }
}
