// Catalog API: list drugs with in-date stock, list a drug's lots, and receive a new lot.
package io.pharmavigil.pharmacy.catalog;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.pharmavigil.common.web.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/drugs")
@PreAuthorize("hasAnyRole('PHARMACIST','ADMIN')")
public class CatalogController {

    public record DrugView(Long id, String productCode, String name, String strength, String doseForm, String rxcui,
            boolean rxOnly, int unitPriceCents, int inDateStock) {
    }

    public record LotView(Long id, String lotNumber, LocalDate expiryDate, int quantity, boolean expired) {
    }

    public record ReceiveLot(@NotBlank @Size(max = 64) String lotNumber, @NotNull @Future LocalDate expiryDate,
            @Min(1) @Max(100000) int quantity) {
    }

    private final DrugRepository drugs;
    private final LotRepository lots;
    private final Clock clock;

    public CatalogController(DrugRepository drugs, LotRepository lots, Clock clock) {
        this.drugs = drugs;
        this.lots = lots;
        this.clock = clock;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<DrugView> list() {
        LocalDate today = LocalDate.now(clock);
        return drugs.findAllByOrderByNameAsc().stream()
                .map(d -> new DrugView(d.getId(), d.getProductCode(), d.getName(), d.getStrength(), d.getDoseForm(),
                        d.getRxcui(), d.isRxOnly(), d.getUnitPriceCents(),
                        Fefo.usableStock(lots.findByDrugIdOrderByExpiryDateAsc(d.getId()), today)))
                .toList();
    }

    @GetMapping("/{id}/lots")
    @Transactional(readOnly = true)
    public List<LotView> lots(@PathVariable Long id) {
        LocalDate today = LocalDate.now(clock);
        return lots.findByDrugIdOrderByExpiryDateAsc(id).stream()
                .map(l -> new LotView(l.getId(), l.getLotNumber(), l.getExpiryDate(), l.getQuantity(),
                        !l.getExpiryDate().isAfter(today)))
                .toList();
    }

    @PostMapping("/{id}/lots")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public LotView receive(@PathVariable Long id, @Valid @RequestBody ReceiveLot req) {
        Drug drug = drugs.findById(id).orElseThrow(() -> new NotFoundException("no drug " + id));
        Lot lot = lots.save(new Lot(drug, req.lotNumber(), req.expiryDate(), req.quantity(), clock.instant()));
        return new LotView(lot.getId(), lot.getLotNumber(), lot.getExpiryDate(), lot.getQuantity(), false);
    }
}
