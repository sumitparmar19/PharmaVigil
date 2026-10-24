// Listeners of the gateway: case.ready from Kafka (poison messages go to case.ready.dlq) and acknowledgements
// from the regulator over JMS (a failing message is rolled back, redelivered by the broker and finally moved to
// the broker's dead-letter queue).
package io.pharmavigil.submission.gateway;

import org.springframework.jms.annotation.JmsListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import io.pharmavigil.common.events.CaseReady;
import io.pharmavigil.common.events.Topics;
import io.pharmavigil.common.kafka.PoisonMessageException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class GatewayListeners {

    private final SubmissionService service;
    private final JsonMapper json;

    public GatewayListeners(SubmissionService service, JsonMapper json) {
        this.service = service;
        this.json = json;
    }

    @KafkaListener(id = "gateway-case-ready", topics = Topics.CASE_READY, groupId = "submission-gateway")
    public void caseReady(String payload) {
        CaseReady r;
        try {
            r = json.readValue(payload, CaseReady.class);
        } catch (JacksonException e) {
            throw new PoisonMessageException("not a valid CaseReady", e);
        }
        if (r == null) {
            throw new PoisonMessageException("empty CaseReady", null);
        }
        service.onCaseReady(r);
    }

    @JmsListener(id = "gateway-ack", destination = Jms.ACK)
    public void ack(String body) {
        service.onAck(json.readValue(body, RegulatorAck.class));
    }
}
