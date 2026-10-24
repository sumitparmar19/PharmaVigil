// Base for integration tests: full application context on the shared Oracle (or SQL Server) container,
// MockMvc, and helpers to create users, tokens, drugs, lots and patients with unique names per test.
package io.pharmavigil.pharmacy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import io.pharmavigil.common.security.TokenService;
import io.pharmavigil.common.testing.TestDatabases;
import io.pharmavigil.pharmacy.auth.AppUser;
import io.pharmavigil.pharmacy.auth.AppUserRepository;
import io.pharmavigil.pharmacy.catalog.Drug;
import io.pharmavigil.pharmacy.catalog.DrugRepository;
import io.pharmavigil.pharmacy.catalog.Lot;
import io.pharmavigil.pharmacy.catalog.LotRepository;
import io.pharmavigil.pharmacy.patients.Patient;
import io.pharmavigil.pharmacy.patients.PatientRepository;
import io.pharmavigil.pharmacy.patients.Prescriber;
import io.pharmavigil.pharmacy.patients.PrescriberRepository;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class DbTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry);
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected TokenService tokens;
    @Autowired protected DrugRepository drugs;
    @Autowired protected LotRepository lots;
    @Autowired protected PatientRepository patients;
    @Autowired protected PrescriberRepository prescribers;
    @Autowired protected AppUserRepository users;
    @Autowired protected PasswordEncoder encoder;

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected String bearer(String role) {
        return "Bearer " + tokens.issue(unique("user"), List.of(role));
    }

    protected Drug drug(boolean rxOnly) {
        return drugs.save(new Drug(unique("P"), "83367", unique("Drug"), "10 mg", "tablet", rxOnly, 25));
    }

    protected Lot lot(Drug drug, LocalDate expiry, int quantity) {
        return lots.save(new Lot(drug, unique("L"), expiry, quantity, Instant.now()));
    }

    protected Patient patient() {
        return patients.save(new Patient(unique("syn"), "Test", unique("Fam"), LocalDate.of(1970, 1, 1), "F"));
    }

    protected Prescriber prescriber() {
        return prescribers.save(new Prescriber(unique("PR"), "Test Prescriber"));
    }

    protected AppUser user(String username, String password, String role, Long patientId) {
        return users.save(new AppUser(username, encoder.encode(password), role, "Test " + role, patientId));
    }
}
