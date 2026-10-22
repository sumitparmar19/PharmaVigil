// Reviewer API: confirm or correct codes, seriousness and duplicate flags, write and approve the narrative,
// approve the case, submit it, and read its submission history. Every call is recorded as a decision or an
// audit event under the reviewer's name.
package io.pharmavigil.caseservice.review;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.pharmavigil.caseservice.model.CaseEnums.Seriousness;
import io.pharmavigil.caseservice.model.SubmissionEvent;
import io.pharmavigil.caseservice.model.SubmissionEventRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/cases/{id}")
@PreAuthorize("hasAnyRole('SAFETY_REVIEWER','ADMIN')")
public class ReviewController {

    public record CodeIn(@NotBlank @Size(max = 200) String pt, @NotNull Integer lockVersion) {
    }

    public record SeriousnessIn(@NotNull Seriousness value) {
    }

    public record DuplicateIn(Long duplicateOf) {
    }

    public record NarrativeIn(@NotBlank @Size(max = 4000) String body) {
    }

    public record Result(Long caseId, String status, int version, int lockVersion) {
    }

    public record SubmissionView(int version, String safetyReportId, String messageId, String status, String reason,
            String at) {
        static SubmissionView of(SubmissionEvent e) {
            return new SubmissionView(e.getVersionNo(), e.getSafetyReportId(), e.getMessageId(), e.getStatus(),
                    e.getReason(), e.getOccurredAt().toString());
        }
    }

    private final ReviewService reviews;
    private final SubmissionEventRepository submissions;
    private final io.pharmavigil.caseservice.cases.CaseService cases;

    public ReviewController(ReviewService reviews, SubmissionEventRepository submissions,
            io.pharmavigil.caseservice.cases.CaseService cases) {
        this.reviews = reviews;
        this.submissions = submissions;
        this.cases = cases;
    }

    private Result result(Long id) {
        var c = cases.load(id);
        return new Result(c.getId(), c.getStatus().name(), c.getCurrentVersion(), c.getLockVersion());
    }

    @PostMapping("/reactions/{index}/code")
    public Result code(@PathVariable Long id, @PathVariable int index, @Valid @RequestBody CodeIn in,
            Authentication auth) {
        reviews.codeReaction(id, index, in.pt(), in.lockVersion(), auth.getName());
        return result(id);
    }

    @PostMapping("/seriousness")
    public Result seriousness(@PathVariable Long id, @Valid @RequestBody SeriousnessIn in, Authentication auth) {
        reviews.decideSeriousness(id, in.value(), auth.getName());
        return result(id);
    }

    @PostMapping("/duplicate")
    public Result duplicate(@PathVariable Long id, @RequestBody DuplicateIn in, Authentication auth) {
        reviews.decideDuplicate(id, in.duplicateOf(), auth.getName());
        return result(id);
    }

    @PostMapping("/narratives")
    @ResponseStatus(HttpStatus.CREATED)
    public Long narrative(@PathVariable Long id, @Valid @RequestBody NarrativeIn in, Authentication auth) {
        return reviews.writeNarrative(id, in.body(), auth.getName()).getId();
    }

    @PostMapping("/narratives/{narrativeId}/approve")
    public Result approveNarrative(@PathVariable Long id, @PathVariable Long narrativeId, Authentication auth) {
        reviews.approveNarrative(id, narrativeId, auth.getName());
        return result(id);
    }

    @PostMapping("/approve")
    public Result approve(@PathVariable Long id, Authentication auth) {
        reviews.approve(id, auth.getName());
        return result(id);
    }

    @PostMapping("/submit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SubmissionView submit(@PathVariable Long id, Authentication auth) {
        return SubmissionView.of(reviews.submit(id, auth.getName()));
    }

    @GetMapping("/submissions")
    public List<SubmissionView> submissions(@PathVariable Long id) {
        cases.load(id);
        return submissions.findByCaseIdOrderByIdAsc(id).stream().map(SubmissionView::of).toList();
    }

    @ExceptionHandler(ReviewService.SubmissionBlockedException.class)
    ProblemDetail blocked(ReviewService.SubmissionBlockedException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        pd.setProperty("errors", e.errors().stream().limit(20).toList());
        return pd;
    }
}
