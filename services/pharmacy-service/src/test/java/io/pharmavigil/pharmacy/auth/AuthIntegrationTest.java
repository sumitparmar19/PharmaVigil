// Login and access control: a failed login yields no token and no session; requests without a token are
// rejected; roles are enforced per endpoint; a patient can read only their own history.
package io.pharmavigil.pharmacy.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import io.pharmavigil.common.security.Roles;
import io.pharmavigil.pharmacy.DbTest;
import io.pharmavigil.pharmacy.patients.Patient;

class AuthIntegrationTest extends DbTest {

    private MvcResult login(String user, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}")).andReturn();
    }

    @Test
    void correctPasswordReturnsTokenWithRole() throws Exception {
        String name = unique("ph");
        user(name, "correct horse battery", Roles.PHARMACIST, null);
        MvcResult r = login(name, "correct horse battery");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentAsString()).contains("\"token\"").contains("PHARMACIST");
        assertThat(r.getResponse().getHeader("Set-Cookie")).isNull();
    }

    @Test
    void failedLoginGivesNoTokenNoSessionAndNoUserEnumeration() throws Exception {
        String name = unique("ph");
        user(name, "correct horse battery", Roles.PHARMACIST, null);
        MvcResult wrong = login(name, "wrong password");
        MvcResult unknown = login(unique("nobody"), "whatever");
        for (MvcResult r : new MvcResult[] {wrong, unknown}) {
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(r.getResponse().getContentAsString()).doesNotContain("token");
            assertThat(r.getResponse().getHeader("Set-Cookie")).isNull();
            assertThat(r.getRequest().getSession(false)).isNull();
        }
        assertThat(wrong.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
    }

    @Test
    void protectedEndpointsNeedAToken() throws Exception {
        mvc.perform(get("/api/drugs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/drugs").header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized());
    }

    @Test
    void rolesAreEnforcedPerEndpoint() throws Exception {
        mvc.perform(post("/api/dispenses").header("Authorization", bearer(Roles.PATIENT))
                .contentType(MediaType.APPLICATION_JSON).content("{\"patientId\":1,\"drugId\":1,\"quantity\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/patients").header("Authorization", bearer(Roles.SAFETY_REVIEWER)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/drugs").header("Authorization", bearer(Roles.PHARMACIST))).andExpect(status().isOk());
    }

    @Test
    void patientSeesOnlyOwnHistory() throws Exception {
        Patient me = patient();
        Patient other = patient();
        String name = unique("pt");
        user(name, "patient password 1", Roles.PATIENT, me.getId());
        String token = "Bearer " + tokens.issue(name, java.util.List.of(Roles.PATIENT));
        mvc.perform(get("/api/patients/{id}/dispenses", me.getId()).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/patients/{id}/dispenses", other.getId()).header("Authorization", token))
                .andExpect(status().isForbidden());
    }
}
