// Demo data for local runs and the end-to-end stack (profile "demo"): a small drug catalog with in-date and
// expired lots, demo prescribers, Synthea-format synthetic patients, and logins whose password comes from
// the PV_DEMO_PASSWORD environment variable (no password is ever stored in the repository).
package io.pharmavigil.pharmacy.config;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.pharmavigil.common.security.Roles;
import io.pharmavigil.pharmacy.auth.AppUser;
import io.pharmavigil.pharmacy.auth.AppUserRepository;
import io.pharmavigil.pharmacy.catalog.Drug;
import io.pharmavigil.pharmacy.catalog.DrugRepository;
import io.pharmavigil.pharmacy.catalog.Lot;
import io.pharmavigil.pharmacy.catalog.LotRepository;
import io.pharmavigil.pharmacy.patients.Prescriber;
import io.pharmavigil.pharmacy.patients.PrescriberRepository;
import io.pharmavigil.pharmacy.patients.SyntheaImporter;

@Component
@Profile("demo")
public class DemoData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoData.class);

    // Ingredient RxCUIs for well-known generics (public RxNorm identifiers; product codes are internal).
    private record Item(String code, String rxcui, String name, String strength, String form, boolean rx, int cents) {
    }

    private static final List<Item> CATALOG = List.of(
            new Item("DEMO-ATOR-20", "83367", "Atorvastatin", "20 mg", "tablet", true, 45),
            new Item("DEMO-LISI-10", "29046", "Lisinopril", "10 mg", "tablet", true, 20),
            new Item("DEMO-METF-500", "6809", "Metformin", "500 mg", "tablet", true, 15),
            new Item("DEMO-WARF-5", "11289", "Warfarin", "5 mg", "tablet", true, 30),
            new Item("DEMO-AMOX-500", "723", "Amoxicillin", "500 mg", "capsule", true, 35),
            new Item("DEMO-SERT-50", "36437", "Sertraline", "50 mg", "tablet", true, 40),
            new Item("DEMO-IBU-200", "5640", "Ibuprofen", "200 mg", "tablet", false, 8),
            new Item("DEMO-APAP-500", "161", "Acetaminophen", "500 mg", "tablet", false, 6),
            new Item("DEMO-LORA-10", "28889", "Loratadine", "10 mg", "tablet", false, 12));

    private final DrugRepository drugs;
    private final LotRepository lots;
    private final PrescriberRepository prescribers;
    private final AppUserRepository users;
    private final SyntheaImporter synthea;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final String demoPassword;

    public DemoData(DrugRepository drugs, LotRepository lots, PrescriberRepository prescribers, AppUserRepository users,
            SyntheaImporter synthea, PasswordEncoder encoder, Clock clock,
            @Value("${PV_DEMO_PASSWORD:}") String demoPassword) {
        this.drugs = drugs;
        this.lots = lots;
        this.prescribers = prescribers;
        this.users = users;
        this.synthea = synthea;
        this.encoder = encoder;
        this.clock = clock;
        this.demoPassword = demoPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (drugs.findByProductCode(CATALOG.get(0).code()).isPresent()) {
            return; // already seeded
        }
        LocalDate today = LocalDate.now(clock);
        for (Item i : CATALOG) {
            Drug d = drugs.save(new Drug(i.code(), i.rxcui(), i.name(), i.strength(), i.form(), i.rx(), i.cents()));
            lots.save(new Lot(d, i.code() + "-L1", today.plusMonths(2), 60, clock.instant()));
            lots.save(new Lot(d, i.code() + "-L2", today.plusMonths(14), 200, clock.instant()));
            lots.save(new Lot(d, i.code() + "-EXP", today.minusDays(10), 500, clock.instant())); // must never be sold
        }
        prescribers.save(new Prescriber("DEMO-PR-1", "Demo Prescriber One"));
        prescribers.save(new Prescriber("DEMO-PR-2", "Demo Prescriber Two"));
        try (var reader = new InputStreamReader(new ClassPathResource("demo/synthea_patients.csv").getInputStream(),
                StandardCharsets.UTF_8)) {
            log.info("Synthea import: {}", synthea.importCsv(reader));
        }
        if (demoPassword.length() >= 12) {
            String hash = encoder.encode(demoPassword);
            users.save(new AppUser("pharmacist", hash, Roles.PHARMACIST, "Demo Pharmacist", null));
            users.save(new AppUser("reviewer", hash, Roles.SAFETY_REVIEWER, "Demo Safety Reviewer", null));
            users.save(new AppUser("admin", hash, Roles.ADMIN, "Demo Admin", null));
        } else {
            log.warn("PV_DEMO_PASSWORD not set (or shorter than 12 characters): no demo logins created");
        }
    }
}
