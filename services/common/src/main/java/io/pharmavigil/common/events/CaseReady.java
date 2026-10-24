// Payload of case.ready: an approved case whose E2B(R3) export passed the official schemas, ready for the
// submission gateway. The XML travels in the event so the gateway needs no access to case data.
package io.pharmavigil.common.events;

import java.time.Instant;

public record CaseReady(String eventId, long caseId, String caseNumber, int versionNo, String safetyReportId,
        String e2bXml, Instant occurredAt) {
}
