// Read API over the newest signal snapshot. Every response names its data scope and carries the causation
// disclaimer; without a loaded export the lists are empty (never invented). The data are aggregates of public
// FAERS reports, so reads are open; reloading an export needs the ADMIN role. Metrics: counter lookups by result
// (pv_signals_lookups_total) and the loaded snapshot's size and load time (pv_signals_snapshot_*).
package io.pharmavigil.signal.api;

import java.nio.file.Path;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.pharmavigil.common.Disclaimers;
import io.pharmavigil.signal.store.SignalLoader;
import io.pharmavigil.signal.store.SignalStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/signals")
public class SignalController {

    /** The shape pharmacy-service's SignalClient reads (one warning at the counter). */
    public record Warning(String rxcui, String pt, long reports, Double ebgm, Double eb05, Double safetyformer,
            String labelText, String disclaimer) {
    }

    public record Meta(boolean loaded, String scope, Integer pairs, Boolean withSafetyformer, String loadedAt,
            JsonNode evaluation, double warningEb05, String disclaimer) {
    }

    public record Top(String method, String scope, List<SignalStore.Signal> signals, String disclaimer) {
    }

    public record Trend(String rxcui, String pt, String scope, List<SignalStore.TrendPoint> points) {
    }

    private final SignalStore store;
    private final SignalLoader loader;
    private final JsonMapper json;
    private final double warningEb05;
    private final int warningLimit;
    private final String dir;
    private final MeterRegistry meters;

    public SignalController(SignalStore store, SignalLoader loader, JsonMapper json,
            @Value("${pv.signals.warning-eb05:2.0}") double warningEb05,
            @Value("${pv.signals.warning-limit:10}") int warningLimit, @Value("${pv.signals.dir:}") String dir,
            MeterRegistry meters) {
        this.meters = meters;
        Gauge.builder("pv.signals.snapshot.pairs", store, s -> snapshot(s, x -> x.pairs()))
                .description("Drug-event pairs in the loaded signal snapshot").register(meters);
        Gauge.builder("pv.signals.snapshot.loaded", store, s -> snapshot(s, x -> x.loadedAt().getEpochSecond()))
                .description("When the loaded signal snapshot was loaded (Unix seconds)").register(meters);
        this.store = store;
        this.loader = loader;
        this.json = json;
        this.warningEb05 = warningEb05;
        this.warningLimit = warningLimit;
        this.dir = dir;
    }

    @GetMapping("/warnings")
    public List<Warning> warnings(@RequestParam String rxcui) {
        var snapshot = store.current();
        List<Warning> out = snapshot.map(s -> store.warnings(s.id(), rxcui, warningEb05, warningLimit).stream()
                .map(x -> new Warning(x.rxcui(), x.pt(), x.reports(), x.ebgm(), x.eb05(), x.safetyformer(), null,
                        Disclaimers.NO_CAUSATION))
                .toList()).orElse(List.of());
        String result = snapshot.isEmpty() ? "no_data" : out.isEmpty() ? "none" : "warning";
        meters.counter("pv.signals.lookups", "result", result).increment();
        return out;
    }

    /** NaN without a snapshot or while the database is unreachable. */
    static double snapshot(SignalStore store, java.util.function.ToDoubleFunction<SignalStore.Snapshot> value) {
        try {
            return store.current().map(value::applyAsDouble).orElse(Double.NaN);
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    @GetMapping("/top")
    public Top top(@RequestParam(defaultValue = "ebgm") String method, @RequestParam(defaultValue = "50") int limit) {
        String column = switch (method) {
            case "ebgm" -> "eb05";
            case "prr" -> "prr_lo";
            case "ror" -> "ror_lo";
            case "ic" -> "ic025";
            case "safetyformer" -> "safetyformer";
            default -> throw new IllegalArgumentException("method must be ebgm, prr, ror, ic or safetyformer");
        };
        int n = Math.max(1, Math.min(limit, 500));
        return store.current().map(s -> new Top(method, s.scope(), store.top(s.id(), column, n),
                Disclaimers.NO_CAUSATION)).orElse(new Top(method, null, List.of(), Disclaimers.NO_CAUSATION));
    }

    /** Drug search for the signals page: every scored pair of the ingredients starting with q. */
    @GetMapping("/search")
    public Top search(@RequestParam String q, @RequestParam(defaultValue = "50") int limit) {
        if (q.isBlank() || q.length() > 60) {
            throw new IllegalArgumentException("q must be 1 to 60 characters");
        }
        var snap = store.current();
        if (snap.isEmpty()) {
            return new Top("search", null, List.of(), Disclaimers.NO_CAUSATION);
        }
        return new Top("search", snap.get().scope(), store.search(snap.get().id(), q, Math.min(Math.max(limit, 1), 200)),
                Disclaimers.NO_CAUSATION);
    }

    @GetMapping("/trend")
    public Trend trend(@RequestParam String rxcui, @RequestParam String pt) {
        return store.current().map(s -> new Trend(rxcui, pt, s.scope(), store.trend(s.id(), rxcui, pt)))
                .orElse(new Trend(rxcui, pt, null, List.of()));
    }

    @GetMapping("/meta")
    public Meta meta() {
        return store.current().map(s -> new Meta(true, s.scope(), s.pairs(), s.withSafetyformer(),
                s.loadedAt().toString(), s.evaluationJson() == null ? null : json.readTree(s.evaluationJson()),
                warningEb05, Disclaimers.NO_CAUSATION))
                .orElse(new Meta(false, null, null, null, null, null, warningEb05, Disclaimers.NO_CAUSATION));
    }

    @PostMapping("/reload")
    @PreAuthorize("hasRole('ADMIN')")
    public Meta reload() {
        if (dir == null || dir.isBlank()) {
            throw new IllegalArgumentException("PV_SIGNALS_DIR is not set");
        }
        loader.load(Path.of(dir));
        return meta();
    }
}
