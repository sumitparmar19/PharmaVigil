// E2B(R3) validator bean: schemas from PV_E2B_XSD_DIR (pv.e2b.xsd-dir). Without them every export reports
// SCHEMA_UNAVAILABLE and none can be queued.
package io.pharmavigil.caseservice.e2b;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.pharmavigil.common.e2b.E2bValidator;

@Configuration
public class E2bConfig {

    @Bean
    E2bValidator e2bValidator(@Value("${pv.e2b.xsd-dir:}") String dir) {
        return new E2bValidator(dir.isBlank() ? null : Path.of(dir));
    }
}
