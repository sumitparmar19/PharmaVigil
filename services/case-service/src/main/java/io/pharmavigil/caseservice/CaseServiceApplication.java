// Entry point for case-service. Case processing: JPA case model on Oracle, pipeline consumers, reviewer API, E2B(R3) export.
package io.pharmavigil.caseservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CaseServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CaseServiceApplication.class, args);
    }
}
