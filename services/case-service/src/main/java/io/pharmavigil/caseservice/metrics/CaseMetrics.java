// Prometheus metrics of the case pipeline (docs/OBSERVABILITY.md): time per stage, intake-to-coded latency,
// model inference time and how often reviewers accept the models' suggestions. Names are Micrometer names;
// Prometheus sees them with dots as underscores (pv.pipeline.stage → pv_pipeline_stage_seconds).
package io.pharmavigil.caseservice.metrics;

import java.time.Clock;
import java.time.Duration;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.pharmavigil.caseservice.model.ReviewDecision;
import io.pharmavigil.caseservice.model.SafetyCase;
import ai.onnxruntime.OrtException;

@Component
public class CaseMetrics {

    /** A model call; ONNX Runtime throws a checked exception. */
    @FunctionalInterface
    public interface Inference<T> {
        T run() throws OrtException;
    }

    private final MeterRegistry registry;
    private final Clock clock;

    public CaseMetrics(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    /** Times one pipeline stage (one Kafka message), tagged with its outcome. */
    public void stage(String stage, Runnable work) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "error";
        try {
            work.run();
            outcome = "ok";
        } finally {
            sample.stop(Timer.builder("pv.pipeline.stage").description("Time to process one pipeline message")
                    .tag("stage", stage).tag("outcome", outcome).publishPercentileHistogram()
                    .maximumExpectedValue(Duration.ofMinutes(2)).register(registry));
        }
    }

    /** Seconds from intake-gateway's acceptance of the latest report (first or follow-up) to the end of coding. */
    public void coded(SafetyCase c) {
        if (c.getIntakeReceivedAt() == null) {
            return;
        }
        Duration d = Duration.between(c.getIntakeReceivedAt(), clock.instant());
        if (!d.isNegative()) {
            Timer.builder("pv.report.intake_to_coded").description("Intake acceptance to coded case")
                    .publishPercentileHistogram().maximumExpectedValue(Duration.ofMinutes(30)).register(registry)
                    .record(d);
        }
    }

    public <T> T inference(String model, Inference<T> call) throws OrtException {
        Timer.Sample sample = Timer.start(registry);
        try {
            return call.run();
        } finally {
            sample.stop(Timer.builder("pv.model.inference").description("ONNX Runtime inference time")
                    .tag("model", model).publishPercentileHistogram().register(registry));
        }
    }

    /** pv_pipeline_outcome_total{stage, result}: e.g. coding CODED vs NEEDS_MANUAL_CODING, triage SERIOUS. */
    public void outcome(String stage, String result) {
        Counter.builder("pv.pipeline.outcome").description("Result of an automatic pipeline stage per case")
                .tag("stage", stage).tag("result", result).register(registry).increment();
    }

    /** accepted: the reviewer kept the model's suggestion; corrected: changed it; manual: there was none. */
    public void decision(ReviewDecision d) {
        String outcome = d.getModelName() == null && d.getModelSuggestion() == null ? "manual"
                : d.isAccepted() ? "accepted" : "corrected";
        Counter.builder("pv.review.decisions").description("Reviewer decisions on AI suggestions")
                .tag("field", d.getField()).tag("outcome", outcome).register(registry).increment();
    }
}
