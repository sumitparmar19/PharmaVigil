// signal-service HTTP security: signal reads are open (aggregates of public FAERS data, read by pharmacy-service
// and the signals page); everything else needs a JWT, and reload is ADMIN-only (@PreAuthorize).
package io.pharmavigil.signal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
                        .requestMatchers(HttpMethod.GET, "/api/signals/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }
}
