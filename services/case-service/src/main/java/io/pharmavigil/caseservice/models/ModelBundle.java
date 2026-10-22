// Loads the model bundle written by `pv-ml java-bundle` (or a trained bundle) from PV_MODEL_DIR. Each model is
// loaded on its own: a missing or broken file disables only that model and is logged, and the pipeline
// then routes cases to manual coding or "seriousness unknown" (engineering rule 2). Nothing here calls Python.
package io.pharmavigil.caseservice.models;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ModelBundle implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ModelBundle.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<OrtSession> sessions = new ArrayList<>();
    private final String kind;
    private final SeriousnessModel seriousness;
    private final DuplicateModel duplicates;
    private final MedDraCoder coder;

    public ModelBundle(@Value("${pv.models.dir:}") String dir) {
        Path root = dir == null || dir.isBlank() ? null : Path.of(dir);
        JsonNode meta = root == null ? null : read(root.resolve("bundle.json"));
        this.kind = meta == null ? "none" : meta.path("kind").asString("unknown");
        this.seriousness = root == null ? null : load("seriousness", () -> seriousness(root, meta));
        this.duplicates = root == null ? null : load("duplicates", () -> duplicates(root, meta));
        this.coder = root == null ? null : load("coder", () -> coder(root, meta));
        if (root == null) {
            log.warn("PV_MODEL_DIR is not set: every case goes to manual coding and seriousness stays unknown");
        }
    }

    public String kind() {
        return kind;
    }

    public Optional<SeriousnessModel> seriousness() {
        return Optional.ofNullable(seriousness);
    }

    public Optional<DuplicateModel> duplicates() {
        return Optional.ofNullable(duplicates);
    }

    public Optional<MedDraCoder> coder() {
        return Optional.ofNullable(coder);
    }

    private interface Loader<T> {
        T load() throws Exception;
    }

    private <T> T load(String name, Loader<T> loader) {
        try {
            T model = loader.load();
            log.info("model {} loaded ({} bundle)", name, kind);
            return model;
        } catch (Exception e) {
            log.warn("model {} unavailable: {}", name, e.toString());
            return null;
        }
    }

    private ModelInfo info(String name, Path onnx, JsonNode meta) {
        String sha = meta == null ? "" : meta.path("files").path(onnx.getFileName().toString()).asString("");
        return new ModelInfo(name, kind + ":" + (sha.length() >= 12 ? sha.substring(0, 12) : "unhashed"));
    }

    private OrtSession session(Path onnx) throws OrtException {
        if (!Files.isRegularFile(onnx)) {
            throw new IllegalStateException(onnx + " not found");
        }
        OrtSession s = Onnx.open(onnx);
        sessions.add(s);
        return s;
    }

    private SeriousnessModel seriousness(Path root, JsonNode meta) throws OrtException {
        JsonNode spec = required(root.resolve("seriousness_features.json"));
        Path onnx = root.resolve("seriousness.onnx");
        List<SeriousnessModel.AgeBand> bands = new ArrayList<>();
        for (JsonNode b : spec.path("age_bands")) {
            bands.add(new SeriousnessModel.AgeBand(b.get(0).asDouble(), b.get(1).asDouble(), b.get(2).asString()));
        }
        return new SeriousnessModel(session(onnx), info("seriousness-lightgbm", onnx, meta),
                strings(spec.path("columns")), bands, spec.path("outcome_revealing_pattern").asString());
    }

    private DuplicateModel duplicates(Path root, JsonNode meta) throws OrtException {
        JsonNode spec = required(root.resolve("duplicates.json"));
        Path onnx = root.resolve("duplicates.onnx");
        if (spec.path("features").size() != 10) {
            throw new IllegalStateException("duplicates.json must list the 10 pair features");
        }
        return new DuplicateModel(session(onnx), info("duplicates-lightgbm", onnx, meta),
                spec.path("threshold").asDouble(0.5));
    }

    private MedDraCoder coder(Path root, JsonNode meta) throws OrtException, IOException {
        JsonNode cfg = required(root.resolve("coder.json"));
        Path onnx = root.resolve("coder.onnx");
        List<String> vocab = Files.readAllLines(root.resolve("coder_vocab.txt"));
        while (!vocab.isEmpty() && vocab.get(vocab.size() - 1).isEmpty()) {
            vocab.remove(vocab.size() - 1);
        }
        WordPieceTokenizer tok = new WordPieceTokenizer(vocab, cfg.path("lowercase").asBoolean(true),
                cfg.path("add_special_tokens").asBoolean(true));
        JsonNode tailEmb = cfg.path("tail_embeddings");
        float[][] emb = new float[tailEmb.size()][];
        for (int i = 0; i < emb.length; i++) {
            JsonNode row = tailEmb.get(i);
            emb[i] = new float[row.size()];
            for (int d = 0; d < row.size(); d++) {
                emb[i][d] = (float) row.get(d).asDouble();
            }
        }
        return new MedDraCoder(session(onnx), info("meddra-coder", onnx, meta), tok, strings(cfg.path("head")),
                strings(cfg.path("tail")), emb, cfg.path("tail_weight").asDouble(0), cfg.path("max_length").asInt(128),
                cfg.path("threshold").asDouble(0.5));
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    private static JsonNode read(Path file) {
        try {
            return Files.isRegularFile(file) ? JSON.readTree(file.toFile()) : null;
        } catch (RuntimeException e) {
            log.warn("cannot read {}: {}", file, e.toString());
            return null;
        }
    }

    private static JsonNode required(Path file) {
        JsonNode n = read(file);
        if (n == null) {
            throw new IllegalStateException(file + " missing or unreadable");
        }
        return n;
    }

    @Override
    public void destroy() {
        for (OrtSession s : sessions) {
            try {
                s.close();
            } catch (OrtException e) {
                log.debug("closing ONNX session: {}", e.toString());
            }
        }
    }
}
