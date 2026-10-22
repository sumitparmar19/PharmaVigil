// Read API: the messages sent for a case and the regulator's answers (for the reviewer console and support).
package io.pharmavigil.submission.gateway;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.pharmavigil.submission.model.SubmissionMessage;
import io.pharmavigil.submission.model.SubmissionMessageRepository;

@RestController
@PreAuthorize("hasAnyRole('SAFETY_REVIEWER','ADMIN')")
public class SubmissionController {

    public record MessageView(String messageId, int version, String safetyReportId, String status, String reason,
            String queuedAt, String answeredAt) {
        static MessageView of(SubmissionMessage m) {
            return new MessageView(m.getMessageId(), m.getVersionNo(), m.getSafetyReportId(), m.getStatus(),
                    m.getReason(), m.getQueuedAt().toString(),
                    m.getAnsweredAt() == null ? null : m.getAnsweredAt().toString());
        }
    }

    private final SubmissionMessageRepository messages;

    public SubmissionController(SubmissionMessageRepository messages) {
        this.messages = messages;
    }

    @GetMapping("/api/submissions/case/{caseId}")
    public List<MessageView> forCase(@PathVariable long caseId) {
        return messages.findByCaseIdOrderByIdAsc(caseId).stream().map(MessageView::of).toList();
    }
}
