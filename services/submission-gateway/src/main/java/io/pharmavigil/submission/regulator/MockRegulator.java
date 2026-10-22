// Stand-in for a regulator's E2B(R3) gateway (FDA ESG / EMA EudraVigilance are not reachable from a demo). It
// receives each message on regulatory.outbound, validates it against the official ICH schemas and answers on
// regulatory.ack in the same transacted JMS session: ACK when valid, NACK with the first errors otherwise (also
// when the schema set is not installed: it cannot accept what it cannot check). Enabled with
// pv.regulator.mock.enabled (default true); a real deployment turns it off.
package io.pharmavigil.submission.regulator;

import java.time.Clock;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

import io.pharmavigil.common.e2b.E2bValidator;
import io.pharmavigil.submission.gateway.Jms;
import io.pharmavigil.submission.gateway.RegulatorAck;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "pv.regulator.mock.enabled", havingValue = "true", matchIfMissing = true)
public class MockRegulator {

    private static final Logger log = LoggerFactory.getLogger(MockRegulator.class);
    static final int MAX_REASONS = 3;

    private final E2bValidator validator;
    private final JmsTemplate jms;
    private final JsonMapper json;
    private final Clock clock;

    public MockRegulator(E2bValidator validator, JmsTemplate jms, JsonMapper json, Clock clock) {
        this.validator = validator;
        this.jms = jms;
        this.json = json;
        this.clock = clock;
    }

    @JmsListener(id = "mock-regulator", destination = Jms.OUTBOUND)
    public void receive(TextMessage message) throws JMSException {
        String messageId = message.getStringProperty(Jms.MESSAGE_ID);
        if (messageId == null) {
            throw new IllegalArgumentException("submission without " + Jms.MESSAGE_ID); // → redelivery, then DLQ
        }
        E2bValidator.Result result = validator.validate(message.getText() == null ? "" : message.getText());
        RegulatorAck ack = switch (result.outcome()) {
            case VALID -> new RegulatorAck(messageId, message.getStringProperty(Jms.SAFETY_REPORT_ID), "ACK", null,
                    clock.instant());
            case INVALID -> nack(message, messageId, "schema validation failed: "
                    + result.errors().stream().limit(MAX_REASONS).collect(Collectors.joining(" | ")));
            case SCHEMA_UNAVAILABLE -> nack(message, messageId, "regulator cannot validate: " + result.errors().get(0));
        };
        log.info("mock regulator {} message {}", ack.outcome(), messageId);
        String body = json.writeValueAsString(ack);
        jms.send(Jms.ACK, session -> session.createTextMessage(body));
    }

    private RegulatorAck nack(TextMessage m, String messageId, String reason) throws JMSException {
        return new RegulatorAck(messageId, m.getStringProperty(Jms.SAFETY_REPORT_ID), "NACK", reason, clock.instant());
    }
}
