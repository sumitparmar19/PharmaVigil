// Payload of the internal pipeline topics (report.deduped, report.coded, case.triaged): which case and which
// version the stage worked on. Consumers reload the case from the database, so the event stays small and
// never carries patient details.
package io.pharmavigil.common.events;

import java.time.Instant;

public record CaseEvent(String eventId, long caseId, String caseNumber, int versionNo, String status,
        Instant occurredAt) {
}
