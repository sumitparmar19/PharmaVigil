// Drug catalog lookups.
package io.pharmavigil.pharmacy.catalog;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DrugRepository extends JpaRepository<Drug, Long> {

    Optional<Drug> findByProductCode(String productCode);

    List<Drug> findAllByOrderByNameAsc();
}
