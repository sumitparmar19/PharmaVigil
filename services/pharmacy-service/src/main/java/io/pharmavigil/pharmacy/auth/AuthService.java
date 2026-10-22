// Verifies a username/password and issues a JWT. A failed login returns nothing: no token, no session,
// and the same answer for an unknown user and a wrong password (no user enumeration).
package io.pharmavigil.pharmacy.auth;

import java.util.List;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import io.pharmavigil.common.security.TokenService;

@Service
public class AuthService {

    public record Session(String token, String username, String displayName, List<String> roles, Long patientId) {
    }

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    // Hash of a random value, checked when the user is unknown so response time does not reveal it.
    private final String dummyHash;

    public AuthService(AppUserRepository users, PasswordEncoder encoder, TokenService tokens) {
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    public Optional<Session> login(String username, String password) {
        Optional<AppUser> user = users.findByUsername(username).filter(AppUser::isActive);
        boolean ok = encoder.matches(password, user.map(AppUser::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !ok) {
            return Optional.empty();
        }
        AppUser u = user.get();
        List<String> roles = List.of(u.getRole());
        return Optional.of(new Session(tokens.issue(u.getUsername(), roles), u.getUsername(), u.getDisplayName(), roles,
                u.getPatientId()));
    }
}
