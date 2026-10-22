// The regulator's answer to one submitted message: ACK, or NACK with the reason (schema errors, unreadable file).
package io.pharmavigil.submission.gateway;

import java.time.Instant;

public record RegulatorAck(String messageId, String safetyReportId, String outcome, String reason, Instant at) {
}
