// JWT settings shared by all services. The signing secret comes only from the environment (JWT_SECRET).
package io.pharmavigil.common.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pv.jwt")
public record JwtProperties(String secret, String issuer, Duration ttl) {

    public JwtProperties {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("pv.jwt.secret (JWT_SECRET) must be set and at least 32 characters");
        }
        issuer = issuer == null ? "pharmavigil" : issuer;
        ttl = ttl == null ? Duration.ofHours(8) : ttl;
    }
}
