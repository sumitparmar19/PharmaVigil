// Side-effect reports and the catalog: text is stored and returned exactly as typed (JSON-encoded, never
// HTML), invalid input is rejected with problem details, stock counts ignore expired lots.
package io.pharmavigil.pharmacy.reports;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import io.pharmavigil.common.security.Roles;
import io.pharmavigil.pharmacy.DbTest;
import io.pharmavigil.pharmacy.catalog.Drug;
import io.pharmavigil.pharmacy.patients.Patient;

class ReportAndCatalogIntegrationTest extends DbTest {

    @Test
    void reportTextIsStoredVerbatimAndServedAsJson() throws Exception {
        Drug d = drug(false);
        Patient p = patient();
        String text = "Rash after 2 days <script>alert(1)</script> & itching";
        String json = "{\"patientId\":" + p.getId() + ",\"drugId\":" + d.getId() + ",\"narrative\":"
                + "\"" + text + "\",\"onsetDate\":\"" + LocalDate.now().minusDays(1) + "\"}";
        mvc.perform(post("/api/reports").header("Authorization", bearer(Roles.PHARMACIST))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.narrative").value(text))
                .andExpect(jsonPath("$.status").value("PENDING_SUBMISSION"));
        mvc.perform(get("/api/reports").header("Authorization", bearer(Roles.ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].narrative").exists());
    }

    @Test
    void invalidReportsAreRejected() throws Exception {
        Drug d = drug(false);
        Patient p = patient();
        String future = "{\"patientId\":" + p.getId() + ",\"drugId\":" + d.getId()
                + ",\"narrative\":\"x\",\"onsetDate\":\"" + LocalDate.now().plusDays(3) + "\"}";
        mvc.perform(post("/api/reports").header("Authorization", bearer(Roles.PHARMACIST))
                .contentType(MediaType.APPLICATION_JSON).content(future)).andExpect(status().isBadRequest());
        String tooLong = "{\"patientId\":" + p.getId() + ",\"drugId\":" + d.getId() + ",\"narrative\":\""
                + "a".repeat(4001) + "\"}";
        mvc.perform(post("/api/reports").header("Authorization", bearer(Roles.PHARMACIST))
                .contentType(MediaType.APPLICATION_JSON).content(tooLong)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/reports").header("Authorization", bearer(Roles.PHARMACIST))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"patientId\":999999999,\"drugId\":" + d.getId() + ",\"narrative\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void catalogCountsOnlyInDateStockAndReceivesLots() throws Exception {
        Drug d = drug(false);
        lot(d, LocalDate.now().minusDays(2), 70);
        lot(d, LocalDate.now().plusDays(40), 5);
        mvc.perform(get("/api/drugs").header("Authorization", bearer(Roles.PHARMACIST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + d.getId() + ")].inDateStock").value(5));
        mvc.perform(post("/api/drugs/{id}/lots", d.getId()).header("Authorization", bearer(Roles.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lotNumber\":\"NEW-1\",\"expiryDate\":\"" + LocalDate.now().plusYears(1)
                                + "\",\"quantity\":12}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/drugs/{id}/lots", d.getId()).header("Authorization", bearer(Roles.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lotNumber\":\"OLD-1\",\"expiryDate\":\"2020-01-01\",\"quantity\":12}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/drugs/{id}/lots", d.getId()).header("Authorization", bearer(Roles.PHARMACIST)))
                .andExpect(jsonPath("$[0].expired").value(true));
    }

    @Test
    void pharmacistCanSearchPatientsAndAddPrescriptions() throws Exception {
        Patient p = patient();
        Drug d = drug(true);
        Long prescriber = prescriber().getId();
        mvc.perform(get("/api/patients").param("familyName", p.getFamilyName().substring(0, 6))
                        .header("Authorization", bearer(Roles.PHARMACIST)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].synthetic").value(true));
        mvc.perform(post("/api/patients/{id}/prescriptions", p.getId()).header("Authorization", bearer(Roles.PHARMACIST))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"drugId\":" + d.getId() + ",\"prescriberId\":" + prescriber + ",\"writtenDate\":\""
                                + LocalDate.now() + "\",\"quantity\":30}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.remainingQuantity").value(30));
        mvc.perform(get("/api/patients/{id}/prescriptions", p.getId()).header("Authorization", bearer(Roles.PHARMACIST)))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }
}
