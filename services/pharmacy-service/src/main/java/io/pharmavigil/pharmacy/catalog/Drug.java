// A dispensable product: RxNorm ingredient (RxCUI), strength, form, Rx-only flag and unit price in cents.
package io.pharmavigil.pharmacy.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "drug")
public class Drug {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_code", nullable = false, length = 32)
    private String productCode;

    @Column(length = 16)
    private String ndc;

    @Column(nullable = false, length = 16)
    private String rxcui;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 64)
    private String strength;

    @Column(name = "dose_form", length = 64)
    private String doseForm;

    @Column(name = "rx_only", nullable = false)
    private boolean rxOnly;

    @Column(name = "unit_price_cents", nullable = false)
    private int unitPriceCents;

    protected Drug() {
    }

    public Drug(String productCode, String rxcui, String name, String strength, String doseForm, boolean rxOnly,
            int unitPriceCents) {
        this.productCode = productCode;
        this.rxcui = rxcui;
        this.name = name;
        this.strength = strength;
        this.doseForm = doseForm;
        this.rxOnly = rxOnly;
        this.unitPriceCents = unitPriceCents;
    }

    public Long getId() { return id; }
    public String getProductCode() { return productCode; }
    public String getNdc() { return ndc; }
    public String getRxcui() { return rxcui; }
    public String getName() { return name; }
    public String getStrength() { return strength; }
    public String getDoseForm() { return doseForm; }
    public boolean isRxOnly() { return rxOnly; }
    public int getUnitPriceCents() { return unitPriceCents; }
}
