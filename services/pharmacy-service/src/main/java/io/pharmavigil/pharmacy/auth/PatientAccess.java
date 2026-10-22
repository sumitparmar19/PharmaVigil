// Authorization helper: a PATIENT may read only the patient record linked to their own login.
package io.pharmavigil.pharmacy.auth;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("patientAccess")
public class PatientAccess {

    private final AppUserRepository users;

    public PatientAccess(AppUserRepository users) {
        this.users = users;
    }

    public boolean isSelf(Long patientId, Authentication authentication) {
        return users.findByUsername(authentication.getName())
                .map(u -> patientId != null && patientId.equals(u.getPatientId()))
                .orElse(false);
    }
}
