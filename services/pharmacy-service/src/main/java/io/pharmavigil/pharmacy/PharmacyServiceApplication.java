// Entry point for pharmacy-service. Pharmacy counter: drugs, lots, FEFO dispensing, side-effect reports and safety warnings.
package io.pharmavigil.pharmacy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PharmacyServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PharmacyServiceApplication.class, args);
    }
}
