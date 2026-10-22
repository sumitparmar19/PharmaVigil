// signal-service on the synthetic export fixture: the export loads once as a snapshot, warnings follow the EB05
// rule with the disclaimer, rankings and trends come from the snapshot, a broken export never replaces a good
// one, and only an admin can reload.
package io.pharmavigil.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import io.pharmavigil.common.security.Roles;
import io.pharmavigil.common.security.TokenService;
import io.pharmavigil.common.testing.TestDatabases;
import io.pharmavigil.signal.store.SignalLoader;
import io.pharmavigil.signal.store.SignalStore;

@SpringBootTest
@AutoConfigureMockMvc
class SignalServiceTest {

    static final Path FIXTURE = Path.of("src/test/resources/signal-export");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired SignalStore store;
    @Autowired SignalLoader loader;
    @Autowired TokenService tokens;

    @Test
    void fixtureLoadsOnceAsTheCurrentSnapshot() throws IOException {
        SignalStore.Snapshot s = store.current().orElseThrow();
        assertThat(s.scope()).startsWith("synthetic test fixture");
        long rows = Files.readAllLines(FIXTURE.resolve("signals.csv")).size() - 1;
        assertThat(s.pairs()).isEqualTo(rows);
        SignalLoader.LoadResult again = loader.load(FIXTURE);
        assertThat(again.loaded()).isFalse();
        assertThat(again.snapshot().id()).isEqualTo(s.id());
    }

    @Test
    void warningsFollowTheEb05RuleAndCarryTheDisclaimer() throws Exception {
        mvc.perform(get("/api/signals/warnings").param("rxcui", "rx1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pt").value("acute kidney injury"))
                .andExpect(jsonPath("$[0].reports").value(24))
                .andExpect(jsonPath("$[0].disclaimer").value("Spontaneous reports do not prove that a drug caused a reaction."))
                .andExpect(jsonPath("$[*].eb05", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1.5))));
        mvc.perform(get("/api/signals/warnings").param("rxcui", "unknown-drug"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void rankingsTrendAndMeta() throws Exception {
        mvc.perform(get("/api/signals/top").param("method", "ebgm").param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signals.length()").value(3))
                .andExpect(jsonPath("$.signals[0].rxcui").value("rx1"))
                .andExpect(jsonPath("$.scope").value(org.hamcrest.Matchers.startsWith("synthetic")));
        mvc.perform(get("/api/signals/top").param("method", "prr")).andExpect(status().isOk());
        mvc.perform(get("/api/signals/top").param("method", "safetyformer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.signals").isEmpty());
        mvc.perform(get("/api/signals/top").param("method", "magic")).andExpect(status().isBadRequest());
        List<SignalStore.TrendPoint> points = store.trend(store.current().orElseThrow().id(), "rx1",
                "acute kidney injury");
        assertThat(points.stream().mapToLong(SignalStore.TrendPoint::reports).sum()).isEqualTo(24);
        mvc.perform(get("/api/signals/trend").param("rxcui", "rx1").param("pt", "acute kidney injury"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.points.length()").value(points.size()));
        mvc.perform(get("/api/signals/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loaded").value(true))
                .andExpect(jsonPath("$.evaluation['primary_suspect/omop']").exists());
    }

    @Test
    void brokenExportNeverReplacesTheCurrentSnapshot(@TempDir Path dir) throws IOException {
        long before = store.current().orElseThrow().id();
        Files.copy(FIXTURE.resolve("signals_meta.json"), dir.resolve("signals_meta.json"));
        Files.copy(FIXTURE.resolve("signal_trend.csv"), dir.resolve("signal_trend.csv"));
        Files.writeString(dir.resolve("signals.csv"), "rxcui,ingredient,pt,a\nrx1,drug1,,5\n");
        assertThatThrownBy(() -> loader.load(dir)).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.current().orElseThrow().id()).isEqualTo(before);
        Files.delete(dir.resolve("signals.csv"));
        assertThatThrownBy(() -> loader.load(dir)).hasMessageContaining("export-signals");
    }

    @Test
    void onlyAdminsReload() throws Exception {
        mvc.perform(post("/api/signals/reload")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/signals/reload").header("Authorization",
                "Bearer " + tokens.issue("p", List.of(Roles.PHARMACIST)))).andExpect(status().isForbidden());
        mvc.perform(post("/api/signals/reload").header("Authorization",
                "Bearer " + tokens.issue("a", List.of(Roles.ADMIN))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.loaded").value(true));
    }
}
