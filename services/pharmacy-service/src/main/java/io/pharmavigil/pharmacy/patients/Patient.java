// A synthetic patient (Synthea-format import or demo data). No real patient data is ever stored.
package io.pharmavigil.pharmacy.patients;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "patient")
public class Patient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "synthetic_id", nullable = false, length = 64)
    private String syntheticId;

    @Column(name = "given_name", nullable = false, length = 100)
    private String givenName;

    @Column(name = "family_name", nullable = false, length = 100)
    private String familyName;

    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    @Column(nullable = false, length = 8)
    private String sex;

    @Column(nullable = false)
    private boolean synthetic = true;

    protected Patient() {
    }

    public Patient(String syntheticId, String givenName, String familyName, LocalDate birthDate, String sex) {
        this.syntheticId = syntheticId;
        this.givenName = givenName;
        this.familyName = familyName;
        this.birthDate = birthDate;
        this.sex = sex;
    }

    public Long getId() { return id; }
    public String getSyntheticId() { return syntheticId; }
    public String getGivenName() { return givenName; }
    public String getFamilyName() { return familyName; }
    public LocalDate getBirthDate() { return birthDate; }
    public String getSex() { return sex; }
    public boolean isSynthetic() { return synthetic; }
}
