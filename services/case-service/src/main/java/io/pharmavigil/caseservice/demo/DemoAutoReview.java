// Demo mode only (pv.demo.enabled=true): reviews the cases that the live demo's "Run the pipeline" button
// replays, so a visitor can watch a real FAERS report travel all the way to the mock regulator's answer.
// It acts through the same ReviewService calls a person makes, as the actor "demo-auto-review", and every
// decision is stored next to the model suggestion like a human one; the UI labels these cases "auto-reviewed
// for the demo". It accepts the model's top code (or, when the model gave none, the term exactly as FDA coded
// it in FAERS), the model's seriousness (or the reported criteria), and the drafted narrative. It never touches
// a case without the replay prefix, and never a case that already has a submission (a NACK stays in review).
package io.pharmavigil.caseservice.demo;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.pharmavigil.caseservice.cases.CaseService;
import io.pharmavigil.caseservice.model.CaseEnums.CodeSource;
import io.pharmavigil.caseservice.model.CaseEnums.NarrativeSource;
import io.pharmavigil.caseservice.model.CaseEnums.Seriousness;
import io.pharmavigil.caseservice.model.CaseEnums.Status;
import io.pharmavigil.caseservice.model.CaseVersion;
import io.pharmavigil.caseservice.model.Narrative;
import io.pharmavigil.caseservice.model.NarrativeRepository;
import io.pharmavigil.caseservice.model.SafetyCase;
import io.pharmavigil.caseservice.model.SafetyCaseRepository;
import io.pharmavigil.caseservice.model.SubmissionEventRepository;
import io.pharmavigil.caseservice.review.ReviewService;

@Component
@ConditionalOnProperty(name = "pv.demo.enabled", havingValue = "true")
public class DemoAutoReview {

    /** Source report ids of replayed reports start with this; nothing else is auto-reviewed. */
    public static final String PREFIX = "DEMO-";
    public static final String ACTOR = "demo-auto-review";

    private static final Logger log = LoggerFactory.getLogger(DemoAutoReview.class);
    private static final Set<Status> WAITING = Set.of(Status.TRIAGED, Status.NEEDS_MANUAL_CODING, Status.IN_REVIEW);

    private final SafetyCaseRepository repo;
    private final CaseService cases;
    private final ReviewService review;
    private final NarrativeRepository narratives;
    private final SubmissionEventRepository submissions;
    private final TransactionTemplate tx;

    /** What the review needs from one reaction, read inside a transaction (the collection is lazy). */
    private record Item(CodeSource source, String pt, String reportedTerm) {
    }

    public DemoAutoReview(SafetyCaseRepository repo, CaseService cases, ReviewService review,
            NarrativeRepository narratives, SubmissionEventRepository submissions, TransactionTemplate tx) {
        this.repo = repo;
        this.cases = cases;
        this.review = review;
        this.narratives = narratives;
        this.submissions = submissions;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${pv.demo.auto-review-interval-ms:2000}")
    public void sweep() {
        for (SafetyCase c : repo.findTop50BySourceReportIdStartingWithAndStatusInOrderByIdAsc(PREFIX, WAITING)) {
            try {
                reviewOne(c.getId());
            } catch (RuntimeException e) { // a person reviewing the same case first, a schema failure, ...
                log.info("demo auto-review skipped case {}: {}", c.getCaseNumber(), e.getMessage());
            }
        }
    }

    /** Returns true when the case was submitted; false when it is not ready yet (no narrative draft). */
    boolean reviewOne(Long caseId) {
        if (!submissions.findByCaseIdOrderByIdAsc(caseId).isEmpty()) {
            return false;
        }
        List<Narrative> drafts = narratives.findByCaseIdOrderByIdAsc(caseId);
        if (drafts.isEmpty()) {
            return false; // the narrative stage has not run yet
        }
        List<Item> reactions = tx.execute(t -> {
            CaseVersion v = cases.latest(cases.load(caseId));
            return v.getReactions().stream().map(r -> new Item(r.getCodeSource(), r.getPt(), r.getReportedTerm()))
                    .toList();
        });
        for (int i = 0; i < reactions.size(); i++) {
            Item r = reactions.get(i);
            if (r.source() == CodeSource.REVIEWER) {
                continue;
            }
            String pt = r.pt() != null ? r.pt() : r.reportedTerm();
            review.codeReaction(caseId, i, pt, cases.load(caseId).getLockVersion(), ACTOR);
        }
        Seriousness s = cases.load(caseId).getSeriousness();
        if (s == Seriousness.UNKNOWN) {
            String criteria = tx.execute(t -> cases.latest(cases.load(caseId)).getSeriousCriteria());
            s = criteria == null || criteria.isBlank() ? Seriousness.NON_SERIOUS : Seriousness.SERIOUS;
        }
        review.decideSeriousness(caseId, s, ACTOR);
        Narrative draft = narratives.findByCaseIdOrderByIdAsc(caseId).stream()
                .filter(n -> n.getSource() != NarrativeSource.REVIEWER).reduce((a, b) -> b).orElseThrow();
        if (draft.getApprovedAt() == null) {
            review.approveNarrative(caseId, draft.getId(), ACTOR);
        }
        review.approve(caseId, ACTOR);
        review.submit(caseId, ACTOR);
        return true;
    }
}
