// Public live demo only (Spring profile "demo" with PV_DEMO_PUBLIC_PASSWORD set): tells the landing page which
// shared demo accounts exist and their password, which is shown to every visitor by design (it is set from an
// environment variable on the demo server, never committed). The accounts only reach synthetic patients and
// replayed public FAERS data, and the demo database is reset every night.
package io.pharmavigil.pharmacy.auth;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Profile("demo")
public class DemoAccounts {

    public static final String PHARMACIST = "demo-pharmacist";
    public static final String REVIEWER = "demo-reviewer";
    public static final int MIN_PASSWORD = 8;

    private final String publicPassword;

    public DemoAccounts(@Value("${PV_DEMO_PUBLIC_PASSWORD:}") String publicPassword) {
        this.publicPassword = publicPassword;
    }

    @GetMapping("/demo")
    public Map<String, Object> demo() {
        if (publicPassword.length() < MIN_PASSWORD) {
            return Map.of("enabled", false);
        }
        return Map.of("enabled", true, "password", publicPassword, "accounts", List.of(
                Map.of("username", PHARMACIST, "role", "Pharmacist: dispense, report a side effect, run the pipeline"),
                Map.of("username", REVIEWER, "role", "Safety reviewer: review queue, cases, E2B(R3) files")));
    }
}
