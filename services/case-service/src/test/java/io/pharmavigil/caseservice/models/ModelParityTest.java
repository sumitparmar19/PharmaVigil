// Java vs Python parity on the fixture bundle (pv-ml java-bundle): the tokenizer produces the same ids, the
// seriousness vectorizer the same features, and all three ONNX models the same scores and rankings as the
// Python code that trained them. Also: missing or broken files disable one model, never the service.
package io.pharmavigil.caseservice.models;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ModelParityTest {

    static final Path BUNDLE = Path.of("src/test/resources/model-bundle");
    static ModelBundle bundle;
    static JsonNode parity;

    @BeforeAll
    static void load() {
        bundle = new ModelBundle(BUNDLE.toString());
        parity = JsonMapper.builder().build().readTree(BUNDLE.resolve("parity.json").toFile());
    }

    @AfterAll
    static void close() {
        bundle.destroy();
    }

    private static List<String> strings(JsonNode a) {
        List<String> out = new ArrayList<>();
        a.forEach(n -> out.add(n.asString()));
        return out;
    }

    @Test
    void allModelsLoadWithTraceableVersions() {
        assertThat(bundle.kind()).isEqualTo("fixture");
        assertThat(bundle.seriousness()).isPresent();
        assertThat(bundle.duplicates()).isPresent();
        assertThat(bundle.coder()).isPresent();
        assertThat(bundle.coder().get().info().version()).matches("fixture:[0-9a-f]{12}");
    }

    @Test
    void seriousnessFeaturesAndScoresMatchPython() throws Exception {
        SeriousnessModel m = bundle.seriousness().orElseThrow();
        for (JsonNode row : parity.path("seriousness").path("rows")) {
            JsonNode in = row.path("input");
            List<SeriousnessModel.Drug> drugs = new ArrayList<>();
            in.path("drugs").forEach(d -> drugs.add(new SeriousnessModel.Drug(d.path("rxcui").asString(),
                    d.path("role").asString())));
            SeriousnessModel.Input input = new SeriousnessModel.Input(
                    in.path("age_years").isNull() ? null : new BigDecimal(in.path("age_years").asString()),
                    in.path("sex").asString(), in.path("reporter_type").asString(), in.path("country").asString(),
                    drugs, strings(in.path("pts")));
            float[] x = m.vectorize(input);
            JsonNode expected = row.path("vector");
            assertThat(x).hasSize(expected.size());
            for (int i = 0; i < x.length; i++) {
                assertThat(x[i]).as("feature %d of %s", i, in).isEqualTo((float) expected.get(i).asDouble());
            }
            assertThat(m.predict(input)).isCloseTo(row.path("probability").asDouble(), within(1e-5));
        }
        assertThat(m.isOutcomeRevealing("Sudden death")).isTrue();
        assertThat(m.isOutcomeRevealing("Hospitalisation")).isTrue();
        assertThat(m.isOutcomeRevealing("headache")).isFalse();
    }

    @Test
    void duplicateScoresMatchPython() throws Exception {
        DuplicateModel m = bundle.duplicates().orElseThrow();
        for (JsonNode row : parity.path("duplicates").path("rows")) {
            float[] f = new float[row.path("features").size()];
            for (int i = 0; i < f.length; i++) {
                f[i] = (float) row.path("features").get(i).asDouble();
            }
            assertThat(m.score(f)).isCloseTo(row.path("probability").asDouble(), within(1e-5));
        }
    }

    @Test
    void pairFeaturesFollowThePythonRules() {
        var a = new DuplicateModel.Profile(java.util.Set.of("rx1", "rx2"), java.util.Set.of("rash"), "F", "45-64",
                "US", "PH", java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 1, 10));
        var b = new DuplicateModel.Profile(java.util.Set.of("rx2"), java.util.Set.of(), "UNK", "45-64", "GB",
                null, java.time.LocalDate.of(2026, 1, 4), null);
        assertThat(DuplicateModel.features(a, b)).containsExactly(0.5f, 0f, 1f, 0f, -1f, 1f, 0f, -1f, 3f, -1f);
    }

    @Test
    void tokenizerAndCoderMatchPython() throws Exception {
        MedDraCoder coder = bundle.coder().orElseThrow();
        int k = parity.path("coder").path("k").asInt();
        for (JsonNode row : parity.path("coder").path("rows")) {
            String text = row.path("text").asString();
            long[] ids = coder.tokenizer().encode(text, 32);
            long[] expected = new long[row.path("input_ids").size()];
            for (int i = 0; i < expected.length; i++) {
                expected[i] = row.path("input_ids").get(i).asLong();
            }
            assertThat(ids).as(text).containsExactly(expected);
            MedDraCoder.Raw raw = coder.run(text);
            for (int i = 0; i < raw.headProbabilities().length; i++) {
                assertThat((double) raw.headProbabilities()[i])
                        .isCloseTo(row.path("head_probabilities").get(i).asDouble(), within(1e-4));
            }
            assertThat(coder.suggest(text, k)).extracting(MedDraCoder.Suggestion::pt)
                    .containsExactlyElementsOf(strings(row.path("suggestions")));
        }
        assertThat(coder.suggest("  ", 5)).isEmpty();
    }

    @Test
    void missingOrBrokenFilesDisableOnlyThatModel(@TempDir Path dir) throws IOException {
        ModelBundle none = new ModelBundle("");
        assertThat(none.kind()).isEqualTo("none");
        assertThat(none.coder()).isEmpty();
        assertThat(none.seriousness()).isEmpty();

        for (String f : List.of("bundle.json", "duplicates.json", "duplicates.onnx", "seriousness.onnx",
                "seriousness_features.json", "coder.json", "coder_vocab.txt")) {
            Files.copy(BUNDLE.resolve(f), dir.resolve(f));
        }
        Files.writeString(dir.resolve("seriousness.onnx"), "not a model");
        ModelBundle partial = new ModelBundle(dir.toString());
        assertThat(partial.duplicates()).isPresent();
        assertThat(partial.seriousness()).isEmpty();
        assertThat(partial.coder()).isEmpty(); // coder.onnx was not copied
        partial.destroy();
    }

    @Test
    void tokenizerEdgeCases() {
        WordPieceTokenizer t = new WordPieceTokenizer(List.of("[PAD]", "[UNK]", "[CLS]", "[SEP]", "rash", "##es",
                "red", ",", "x"), true, true);
        assertThat(t.encode("Red RASHES, x", 16)).containsExactly(2, 6, 4, 5, 7, 8, 3);
        assertThat(t.encode("Red rashes, x", 4)).containsExactly(2, 6, 4, 3);
        assertThat(t.encode("zzz\u0000\tred", 8)).containsExactly(2, 1, 6, 3);
        assertThat(t.normalize("Café")).isEqualTo("cafe");
        assertThat(WordPieceTokenizer.preTokenize("a中b")).containsExactly("a中b");
    }
}
