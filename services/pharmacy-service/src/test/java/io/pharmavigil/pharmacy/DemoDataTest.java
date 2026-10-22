// The demo profile seeds a catalog (with expired lots that must never be sold), Synthea-format patients
// and demo logins only when PV_DEMO_PASSWORD is provided.
package io.pharmavigil.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@ActiveProfiles("demo")
@TestPropertySource(properties = "PV_DEMO_PASSWORD=demo-password-for-tests")
class DemoDataTest extends DbTest {

    @Test
    void seedsCatalogPatientsAndLogins() {
        assertThat(drugs.findByProductCode("DEMO-ATOR-20")).isPresent();
        assertThat(patients.findBySyntheticId("demo-0001")).get()
                .satisfies(p -> assertThat(p.getGivenName()).isEqualTo("Alden"));
        assertThat(users.findByUsername("pharmacist")).isPresent();
        assertThat(lots.findAll()).anyMatch(l -> l.getLotNumber().endsWith("-EXP"));
    }
}
