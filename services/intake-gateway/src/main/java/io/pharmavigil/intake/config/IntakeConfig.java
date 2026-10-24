// E2B(R3) reader and validator beans. The schema directory comes from PV_E2B_XSD_DIR (pv.e2b.xsd-dir); when it
// is empty or missing the validator reports SCHEMA_UNAVAILABLE and intake carries on (engineering rule 2).
package io.pharmavigil.intake.config;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.pharmavigil.common.e2b.E2bReader;
import io.pharmavigil.common.e2b.E2bValidator;

@Configuration
public class IntakeConfig {

    @Bean
    E2bReader e2bReader() {
        return new E2bReader();
    }

    @Bean
    E2bValidator e2bValidator(@Value("${pv.e2b.xsd-dir:}") String dir) {
        return new E2bValidator(dir.isBlank() ? null : Path.of(dir));
    }
}
