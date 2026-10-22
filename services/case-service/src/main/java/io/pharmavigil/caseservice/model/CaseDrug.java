// A drug in one case version (RxNorm ingredient when known) with its role: PS primary suspect,
// SS secondary suspect, C concomitant, I interacting. Immutable like its version.
package io.pharmavigil.caseservice.model;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Immutable
@Table(name = "case_drug")
public class CaseDrug {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 16)
    private String rxcui;

    @Column(name = "drug_name", nullable = false, length = 200)
    private String drugName;

    @Column(nullable = false, length = 4)
    private String role;

    @Column(name = "dose_text", length = 200)
    private String doseText;

    @Column(length = 64)
    private String route;

    protected CaseDrug() {
    }

    CaseDrug(String rxcui, String drugName, String role, String doseText, String route) {
        this.rxcui = rxcui;
        this.drugName = drugName;
        this.role = role;
        this.doseText = doseText;
        this.route = route;
    }

    public Long getId() { return id; }
    public String getRxcui() { return rxcui; }
    public String getDrugName() { return drugName; }
    public String getRole() { return role; }
    public String getDoseText() { return doseText; }
    public String getRoute() { return route; }
}
