// A login for the pharmacy app. Passwords are stored only as BCrypt hashes. A PATIENT user is linked to
// their (synthetic) patient record and can read only that record's history.
package io.pharmavigil.pharmacy.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 32)
    private String role;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "patient_id")
    private Long patientId;

    @Column(nullable = false)
    private boolean active = true;

    protected AppUser() {
    }

    public AppUser(String username, String passwordHash, String role, String displayName, Long patientId) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.displayName = displayName;
        this.patientId = patientId;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getRole() { return role; }
    public String getDisplayName() { return displayName; }
    public Long getPatientId() { return patientId; }
    public boolean isActive() { return active; }
}
