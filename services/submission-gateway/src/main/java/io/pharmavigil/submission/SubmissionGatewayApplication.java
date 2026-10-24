// Entry point for submission-gateway. JMS submission gateway and mock regulator: XSD-validates E2B(R3) files and answers ACK/NACK.
package io.pharmavigil.submission;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SubmissionGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SubmissionGatewayApplication.class, args);
    }
}
