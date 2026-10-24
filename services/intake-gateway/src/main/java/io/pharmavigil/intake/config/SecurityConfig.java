// intake-gateway HTTP security: stateless JWT for the REST API (shared setup from common). The SOAP endpoint
// under /ws is open at the HTTP layer because partners authenticate inside the message with WS-Security
// (ADR-009); the WSDL is public by design.
package io.pharmavigil.intake.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import io.pharmavigil.common.security.SecurityAutoConfiguration;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        SecurityAutoConfiguration.statelessJwt(http, converter)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/ws/**", "/actuator/health/**", "/actuator/prometheus",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }
}
