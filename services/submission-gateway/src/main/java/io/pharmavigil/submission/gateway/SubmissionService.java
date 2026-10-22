// The gateway between the case pipeline (Kafka) and the regulator (JMS).
//
// case.ready → the message is recorded (SENDING), the E2B(R3) XML goes on regulatory.outbound in a transacted JMS
// session, then the event is marked processed and QUEUED is written to the outbox. The JMS message id is derived
// from the event id, so if the process dies before the last step the redelivered event resends the same id.
//
// regulatory.ack → the answer is recorded once (a redelivered answer is ignored) and ACK/NACK goes to the outbox.
package io.pharmavigil.submission.gateway;

import java.time.Clock;
import java.util.UUID;

import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.MeterRegistry;
import io.pharmavigil.common.events.CaseReady;
import io.pharmavigil.common.events.SubmissionStatus;
import io.pharmavigil.common.events.Topics;
import io.pharmavigil.common.kafka.PoisonMessageException;
import io.pharmavigil.submission.model.OutboxEvent;
import io.pharmavigil.submission.model.OutboxEventRepository;
import io.pharmavigil.submission.model.ProcessedEvent;
import io.pharmavigil.submission.model.ProcessedEventRepository;
import io.pharmavigil.submission.model.SubmissionMessage;
import io.pharmavigil.submission.model.SubmissionMessageRepository;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SubmissionService {

    static final String CONSUMER = "submission-gateway.case-ready";

    private final JmsTemplate jms;
    private final SubmissionMessageRepository messages;
    private final ProcessedEventRepository processed;
    private final OutboxEventRepository outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;
    private final MeterRegistry registry;

    public SubmissionService(JmsTemplate jms, SubmissionMessageRepository messages, ProcessedEventRepository processed,
            OutboxEventRepository outbox, TransactionTemplate tx, JsonMapper json, Clock clock, MeterRegistry registry) {
        this.registry = registry;
        this.jms = jms;
        this.messages = messages;
        this.processed = processed;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    static String messageId(CaseReady r) {
        return r.caseNumber() + "-V" + r.versionNo() + "-" + r.eventId().substring(0, 8);
    }

    public void onCaseReady(CaseReady r) {
        if (r.eventId() == null || r.eventId().length() < 8 || r.caseNumber() == null || r.safetyReportId() == null
                || r.e2bXml() == null || r.e2bXml().isBlank()) {
            throw new PoisonMessageException("case.ready without eventId, caseNumber, safetyReportId or XML", null);
        }
        if (processed.existsById(new ProcessedEvent.Key(r.eventId(), CONSUMER))) {
            return;
        }
        String messageId = messageId(r);
        // 1. Record the message before sending, so an answer that arrives at once always finds it.
        tx.executeWithoutResult(s -> {
            if (messages.findByMessageId(messageId).isEmpty()) {
                messages.save(new SubmissionMessage(messageId, r.caseId(), r.caseNumber(), r.versionNo(),
                        r.safetyReportId(), clock.instant()));
            }
        });
        // 2. Send in a transacted JMS session (committed when send returns).
        jms.send(Jms.OUTBOUND, session -> {
            var m = session.createTextMessage(r.e2bXml());
            m.setStringProperty(Jms.MESSAGE_ID, messageId);
            m.setStringProperty(Jms.SAFETY_REPORT_ID, r.safetyReportId());
            m.setJMSCorrelationID(messageId);
            return m;
        });
        registry.counter("pv.submissions.sent").increment();
        // 3. Mark the event processed and announce QUEUED, unless a fast answer already did.
        tx.executeWithoutResult(s -> {
            if (processed.existsById(new ProcessedEvent.Key(r.eventId(), CONSUMER))) {
                return;
            }
            processed.saveAndFlush(new ProcessedEvent(r.eventId(), CONSUMER, clock.instant()));
            SubmissionMessage m = messages.lockByMessageId(messageId).orElseThrow();
            if ("SENDING".equals(m.getStatus())) {
                m.queued();
                status(r.caseId(), r.safetyReportId(), messageId, SubmissionStatus.Status.QUEUED, null);
            }
        });
    }

    /** Called by the JMS listener inside its transacted session; an exception rolls the message back. */
    public void onAck(RegulatorAck ack) {
        if (ack.messageId() == null || ack.outcome() == null) {
            throw new IllegalArgumentException("acknowledgement without message id or outcome");
        }
        SubmissionStatus.Status outcome = switch (ack.outcome()) {
            case "ACK" -> SubmissionStatus.Status.ACK;
            case "NACK" -> SubmissionStatus.Status.NACK;
            default -> throw new IllegalArgumentException("unknown outcome " + ack.outcome());
        };
        Boolean recorded = tx.execute(s -> {
            SubmissionMessage m = messages.lockByMessageId(ack.messageId())
                    .orElseThrow(() -> new IllegalArgumentException("acknowledgement for unknown message "
                            + ack.messageId()));
            if (m.answered()) {
                return false; // redelivered answer
            }
            if ("SENDING".equals(m.getStatus())) {
                // The answer overtook step 3 of onCaseReady: announce QUEUED first so consumers see the order.
                status(m.getCaseId(), m.getSafetyReportId(), m.getMessageId(), SubmissionStatus.Status.QUEUED, null);
            }
            m.answer(outcome.name(), ack.reason(), clock.instant());
            status(m.getCaseId(), m.getSafetyReportId(), m.getMessageId(), outcome, ack.reason());
            return true;
        });
        if (Boolean.TRUE.equals(recorded)) {
            registry.counter("pv.regulator.answers", "outcome", outcome.name()).increment();
        }
    }

    private void status(long caseId, String safetyReportId, String messageId, SubmissionStatus.Status s,
            String reason) {
        String id = UUID.randomUUID().toString();
        outbox.save(new OutboxEvent(id, "submission", Long.toString(caseId), Topics.SUBMISSION_STATUS,
                json.writeValueAsString(new SubmissionStatus(id, caseId, safetyReportId, messageId, s, reason,
                        clock.instant())), clock.instant()));
    }
}
