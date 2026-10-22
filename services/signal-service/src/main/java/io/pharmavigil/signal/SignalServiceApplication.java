// Entry point for signal-service. Serves batch-computed drug-event signal scores and the pharmacy warning evidence.
package io.pharmavigil.signal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SignalServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SignalServiceApplication.class, args);
    }
}
