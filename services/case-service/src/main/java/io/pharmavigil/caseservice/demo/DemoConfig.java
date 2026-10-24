// Demo mode switch for case-service: with pv.demo.enabled=true (PV_DEMO=true on the demo server) the scheduled
// demo auto-review runs. Off by default, so normal deployments never auto-review anything.
package io.pharmavigil.caseservice.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "pv.demo.enabled", havingValue = "true")
public class DemoConfig {
}
