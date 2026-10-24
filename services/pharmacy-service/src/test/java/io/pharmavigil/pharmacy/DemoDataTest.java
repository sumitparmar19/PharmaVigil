// The demo profile seeds a catalog (with expired lots that must never be sold), Synthea-format patients
// and demo logins only when PV_DEMO_PASSWORD is provided; with PV_DEMO_PUBLIC_PASSWORD it also creates the two
// shared live-demo accounts and tells the public landing page about them (password shown on purpose).
package io.pharmavigil.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@ActiveProfiles("demo")
@TestPropertySource(properties = {"PV_DEMO_PASSWORD=demo-password-for-tests",
    "PV_DEMO_PUBLIC_PASSWORD=public-demo-pw"})
class DemoDataTest extends DbTest {

    @Test
    void seedsCatalogPatientsAndLogins() {
        assertThat(drugs.findByProductCode("DEMO-ATOR-20")).isPresent();
        assertThat(patients.findBySyntheticId("demo-0001")).get()
                .satisfies(p -> assertThat(p.getGivenName()).isEqualTo("Alden"));
        assertThat(users.findByUsername("pharmacist")).isPresent();
        assertThat(lots.findAll()).anyMatch(l -> l.getLotNumber().endsWith("-EXP"));
    }

    @Test
    void publicDemoAccountsExistAndTheLandingPageCanShowThem() throws Exception {
        assertThat(users.findByUsername("demo-pharmacist")).isPresent();
        assertThat(users.findByUsername("demo-reviewer")).isPresent();
        mvc.perform(get("/api/auth/demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.password").value("public-demo-pw"))
                .andExpect(jsonPath("$.accounts[1].username").value("demo-reviewer"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"demo-reviewer\",\"password\":\"public-demo-pw\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("SAFETY_REVIEWER"));
    }
}
