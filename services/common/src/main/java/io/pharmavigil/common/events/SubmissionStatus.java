// Payload of submission.status: what happened to a submitted ICSR. QUEUED once the message is on the JMS
// queue, then ACK or NACK (with the regulator's reason) when the acknowledgement arrives.
package io.pharmavigil.common.events;

import java.time.Instant;

public record SubmissionStatus(String eventId, long caseId, String safetyReportId, String messageId, Status status,
        String reason, Instant occurredAt) {

    public enum Status { QUEUED, ACK, NACK }
}
