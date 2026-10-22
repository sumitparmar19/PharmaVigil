// Kafka listeners for the case pipeline. They only parse the message and hand it to PipelineStages; a message
// that is not valid JSON of the expected shape is a poison message and goes straight to the dead-letter topic.
package io.pharmavigil.caseservice.pipeline;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import io.pharmavigil.common.events.CaseEvent;
import io.pharmavigil.common.events.ReportReceived;
import io.pharmavigil.common.events.Topics;
import io.pharmavigil.common.kafka.PoisonMessageException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PipelineListeners {

    static final String GROUP = "case-service";

    private final PipelineStages stages;
    private final JsonMapper json;

    public PipelineListeners(PipelineStages stages, JsonMapper json) {
        this.stages = stages;
        this.json = json;
    }

    @KafkaListener(id = "case-received", topics = Topics.REPORT_RECEIVED, groupId = GROUP)
    public void received(String payload) {
        stages.onReportReceived(parse(payload, ReportReceived.class));
    }

    @KafkaListener(id = "case-deduped", topics = Topics.REPORT_DEDUPED, groupId = GROUP)
    public void deduped(String payload) {
        stages.onDeduped(parse(payload, CaseEvent.class));
    }

    @KafkaListener(id = "case-coded", topics = Topics.REPORT_CODED, groupId = GROUP)
    public void coded(String payload) {
        stages.onCoded(parse(payload, CaseEvent.class));
    }

    <T> T parse(String payload, Class<T> type) {
        try {
            T value = json.readValue(payload, type);
            if (value == null) {
                throw new PoisonMessageException("empty " + type.getSimpleName(), null);
            }
            return value;
        } catch (JacksonException | IllegalArgumentException e) {
            throw new PoisonMessageException("not a valid " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }
}
