// Gateway beans: the E2B(R3) validator for the mock regulator (schemas from PV_E2B_XSD_DIR) and HTTP security
// (health and metrics open; the read API needs a reviewer or admin token).
package io.pharmavigil.submission.config;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import io.pharmavigil.common.e2b.E2bValidator;
import io.pharmavigil.common.security.SecurityAutoConfiguration;

@Configuration
@EnableMethodSecurity
public class GatewayConfig {

    @Bean
    E2bValidator e2bValidator(@Value("${pv.e2b.xsd-dir:}") String dir) {
        return new E2bValidator(dir.isBlank() ? null : Path.of(dir));
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        SecurityAutoConfiguration.statelessJwt(http, converter)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }
}
