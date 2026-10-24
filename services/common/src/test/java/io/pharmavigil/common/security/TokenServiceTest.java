// JWT round trip: issued tokens verify with the shared secret, carry roles, and expire.
package io.pharmavigil.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

class TokenServiceTest {

    private final JwtProperties props = new JwtProperties("x".repeat(40), null, Duration.ofMinutes(5));
    private final SecurityAutoConfiguration cfg = new SecurityAutoConfiguration();

    @Test
    void issuedTokenCarriesRolesAndVerifies() {
        TokenService tokens = new TokenService(cfg.jwtEncoder(props), props, Clock.systemUTC());
        Jwt jwt = cfg.jwtDecoder(props).decode(tokens.issue("ana", List.of(Roles.PHARMACIST)));
        assertThat(jwt.getSubject()).isEqualTo("ana");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("PHARMACIST");
        assertThat(cfg.jwtAuthenticationConverter().convert(jwt).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .filteredOn(a -> a.startsWith("ROLE_")) // Spring Security 7 also adds FACTOR_BEARER
                .containsExactly("ROLE_PHARMACIST");
    }

    @Test
    void expiredTokenIsRejected() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        String token = new TokenService(cfg.jwtEncoder(props), props, past).issue("ana", List.of(Roles.ADMIN));
        assertThatThrownBy(() -> cfg.jwtDecoder(props).decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtProperties other = new JwtProperties("y".repeat(40), null, null);
        String token = new TokenService(cfg.jwtEncoder(other), other, Clock.systemUTC())
                .issue("eve", List.of(Roles.ADMIN));
        assertThatThrownBy(() -> cfg.jwtDecoder(props).decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void shortSecretIsRefused() {
        assertThatThrownBy(() -> new JwtProperties("short", null, null)).isInstanceOf(IllegalStateException.class);
        assertThat(new JwtProperties("z".repeat(32), null, null).issuer()).isEqualTo("pharmavigil");
    }
}
