// Entry point for intake-gateway. Report intake: REST from the pharmacy, SOAP (Spring-WS) from partners, E2B(R3) XML import.
package io.pharmavigil.intake;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IntakeGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntakeGatewayApplication.class, args);
    }
}
