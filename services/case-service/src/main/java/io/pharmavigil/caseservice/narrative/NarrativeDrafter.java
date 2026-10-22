// Drafts the case narrative for reviewer approval. With ANTHROPIC_API_KEY and CLAUDE_MODEL set, Claude writes
// it from the structured, already-coded facts only (engineering rule 6); without them, on any API error, on a
// refusal, or on an empty answer, the deterministic template is used. Either way the draft is a suggestion:
// it is stored as a new narrative row and must be approved by a reviewer (engineering rule 5).
package io.pharmavigil.caseservice.narrative;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;

import io.pharmavigil.caseservice.model.CaseEnums.NarrativeSource;
import tools.jackson.databind.json.JsonMapper;

@Component
public class NarrativeDrafter implements DisposableBean {

    public record Draft(String body, NarrativeSource source, String modelName) {
    }

    private static final Logger log = LoggerFactory.getLogger(NarrativeDrafter.class);

    static final String SYSTEM = """
            You write the case narrative (ICH E2B(R3) section H.1) of an individual case safety report for a \
            pharmacovigilance reviewer. You receive the case as JSON. Use only facts present in the JSON; never \
            add, guess or infer anything else. Do not calculate anything: no ages, time intervals or durations \
            that are not given. Do not state or suggest that a drug caused a reaction; use neutral wording such \
            as "was reported". Write plain prose in the third person, past tense, at most 180 words, with no \
            headings, lists or markdown. Refer to the patient only by age and sex.""";

    private final AnthropicClient client;
    private final String model;
    private final long maxTokens;
    private final JsonMapper json;

    public NarrativeDrafter(@Value("${pv.narrative.api-key:}") String apiKey,
            @Value("${pv.narrative.model:}") String model,
            @Value("${pv.narrative.base-url:}") String baseUrl,
            @Value("${pv.narrative.timeout:PT60S}") Duration timeout,
            @Value("${pv.narrative.max-tokens:2000}") long maxTokens, JsonMapper json) {
        this.model = model;
        this.maxTokens = maxTokens;
        this.json = json;
        if (apiKey.isBlank() || model.isBlank()) {
            this.client = null;
            log.info("narratives use the template (ANTHROPIC_API_KEY or CLAUDE_MODEL not set)");
        } else {
            var b = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(timeout).maxRetries(2);
            if (!baseUrl.isBlank()) {
                b.baseUrl(baseUrl);
            }
            this.client = b.build();
        }
    }

    public boolean usesModel() {
        return client != null;
    }

    public Draft draft(CaseFacts facts) {
        if (client == null) {
            return template(facts);
        }
        try {
            Message response = client.messages().create(MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(maxTokens)
                    .system(SYSTEM)
                    .addUserMessage(json.writeValueAsString(facts))
                    .build());
            if (response.stopReason().map(r -> r.equals(StopReason.REFUSAL)).orElse(false)) {
                log.warn("narrative model declined case {}; template used", facts.caseNumber());
                return template(facts);
            }
            String text = String.join("\n", response.content().stream()
                    .flatMap(block -> block.text().stream()).map(t -> t.text()).toList()).strip();
            if (text.isEmpty() || response.stopReason().map(r -> r.equals(StopReason.MAX_TOKENS)).orElse(false)) {
                log.warn("narrative model returned no complete text for case {}; template used", facts.caseNumber());
                return template(facts);
            }
            return new Draft(text, NarrativeSource.LLM, model);
        } catch (AnthropicException e) {
            // Rate limits and server errors were already retried by the SDK; a bad request will not improve.
            log.warn("narrative model unavailable for case {} ({}); template used", facts.caseNumber(),
                    e.getClass().getSimpleName());
            return template(facts);
        }
    }

    static Draft template(CaseFacts facts) {
        return new Draft(TemplateNarrative.render(facts), NarrativeSource.TEMPLATE, null);
    }

    @Override
    public void destroy() {
        if (client != null) {
            client.close();
        }
    }
}
