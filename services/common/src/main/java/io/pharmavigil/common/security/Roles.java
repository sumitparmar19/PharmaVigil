// The four application roles carried in every PharmaVigil JWT (claim "roles").
package io.pharmavigil.common.security;

public final class Roles {

    public static final String PATIENT = "PATIENT";
    public static final String PHARMACIST = "PHARMACIST";
    public static final String SAFETY_REVIEWER = "SAFETY_REVIEWER";
    public static final String ADMIN = "ADMIN";

    private Roles() {
    }
}
