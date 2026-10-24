// Prescriber lookups.
package io.pharmavigil.pharmacy.patients;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PrescriberRepository extends JpaRepository<Prescriber, Long> {

    Optional<Prescriber> findByCode(String code);
}
