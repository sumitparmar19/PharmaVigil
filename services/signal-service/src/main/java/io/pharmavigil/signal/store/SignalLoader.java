// Loads a `pv-ml export-signals` folder as a new snapshot. The SHA-256 of the three files identifies the export:
// loading the same export twice does nothing. Rows are streamed and inserted in batches inside one transaction;
// a malformed file rolls everything back and the previous snapshot stays in service.
package io.pharmavigil.signal.store;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SignalLoader {

    public record LoadResult(SignalStore.Snapshot snapshot, boolean loaded) {
    }

    static final String SIGNALS = "signals.csv";
    static final String TREND = "signal_trend.csv";
    static final String META = "signals_meta.json";
    private static final Logger log = LoggerFactory.getLogger(SignalLoader.class);

    private final SignalStore store;
    private final JsonMapper json;
    private final Clock clock;

    public SignalLoader(SignalStore store, JsonMapper json, Clock clock) {
        this.store = store;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public LoadResult load(Path dir) {
        for (String f : List.of(SIGNALS, TREND, META)) {
            if (!Files.isRegularFile(dir.resolve(f))) {
                throw new IllegalArgumentException(dir.resolve(f) + " not found; run pv-ml export-signals");
            }
        }
        String sha = sha256(dir);
        if (store.hasSource(sha)) {
            return new LoadResult(store.current().orElseThrow(), false);
        }
        JsonNode meta = json.readTree(dir.resolve(META).toFile());
        String scope = meta.path("scope").asString("");
        if (scope.isBlank()) {
            throw new IllegalArgumentException(META + " has no data scope label");
        }
        JsonNode evaluation = meta.path("evaluation");
        long id = store.insertSnapshot(scope.length() > 200 ? scope.substring(0, 200) : scope, sha,
                meta.path("with_safetyformer").asBoolean(false),
                evaluation.isMissingNode() || evaluation.isNull() ? null : json.writeValueAsString(evaluation),
                clock.instant());
        long pairs = stream(dir.resolve(SIGNALS), rows -> store.insertSignals(id, rows), r -> {
            require(r, "rxcui", 16);
            require(r, "pt", 200);
            require(r, "a", 32);
        });
        stream(dir.resolve(TREND), rows -> store.insertTrend(id, rows), r -> {
            require(r, "rxcui", 16);
            require(r, "pt", 200);
            require(r, "year", 8);
        });
        store.setPairs(id, (int) pairs);
        log.info("signal snapshot {} loaded: {} pairs, scope '{}'", id, pairs, scope);
        return new LoadResult(store.current().orElseThrow(), true);
    }

    private interface Sink {
        void write(List<Map<String, String>> rows);
    }

    private interface Check {
        void check(Map<String, String> row);
    }

    private static long stream(Path file, Sink sink, Check check) {
        List<Map<String, String>> buffer = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            long n = Csv.read(in, r -> {
                check.check(r);
                buffer.add(r);
                if (buffer.size() == 1000) {
                    sink.write(buffer);
                    buffer.clear();
                }
            });
            if (!buffer.isEmpty()) {
                sink.write(buffer);
            }
            return n;
        } catch (IOException e) {
            throw new UncheckedIOException(file + ": " + e.getMessage(), e);
        }
    }

    private static void require(Map<String, String> r, String column, int maxLength) {
        String v = r.get(column);
        if (v == null || v.isBlank() || v.length() > maxLength) {
            throw new IllegalArgumentException("column " + column + " missing, empty or too long: " + v);
        }
    }

    static String sha256(Path dir) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // Streamed: the real FAERS export is well over 100 MB, more than the service's heap should hold.
            byte[] chunk = new byte[1 << 16];
            for (String f : List.of(SIGNALS, TREND, META)) {
                try (InputStream in = Files.newInputStream(dir.resolve(f))) {
                    for (int n; (n = in.read(chunk)) > 0; ) {
                        md.update(chunk, 0, n);
                    }
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
