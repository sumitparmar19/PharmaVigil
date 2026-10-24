// case-service security: stateless JWT (shared setup from common), open health/docs endpoints,
// everything else authenticated; per-endpoint roles are enforced with @PreAuthorize.
package io.pharmavigil.caseservice.config;

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
                        .requestMatchers("/api/status", "/actuator/health/**", "/actuator/prometheus",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }
}
