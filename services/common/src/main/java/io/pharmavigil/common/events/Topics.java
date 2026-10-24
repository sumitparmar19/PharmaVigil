// Kafka topic names of the case pipeline (BUILD_INSTRUCTIONS M8). Each stage consumes one topic and, through
// its outbox, publishes the next; every topic has a dead-letter twin "<topic>.dlq" for poison messages.
//
//   report.received → report.deduped → report.coded → case.triaged → (reviewer) → case.ready
//   submission.status: submission-gateway → case-service (queued, ACK, NACK)
package io.pharmavigil.common.events;

import java.util.List;

public final class Topics {

    public static final String REPORT_RECEIVED = ReportReceived.TOPIC;
    public static final String REPORT_DEDUPED = "report.deduped";
    public static final String REPORT_CODED = "report.coded";
    public static final String CASE_TRIAGED = "case.triaged";
    public static final String CASE_READY = "case.ready";
    public static final String SUBMISSION_STATUS = "submission.status";

    public static final List<String> ALL = List.of(REPORT_RECEIVED, REPORT_DEDUPED, REPORT_CODED, CASE_TRIAGED,
            CASE_READY, SUBMISSION_STATUS);

    public static final String DLQ_SUFFIX = ".dlq";

    private Topics() {
    }

    public static String dlq(String topic) {
        return topic + DLQ_SUFFIX;
    }
}
