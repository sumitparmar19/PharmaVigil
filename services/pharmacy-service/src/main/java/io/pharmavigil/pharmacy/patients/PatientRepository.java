// Patient lookups (by synthetic source id, or a family-name prefix for the counter search box).
package io.pharmavigil.pharmacy.patients;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PatientRepository extends JpaRepository<Patient, Long> {

    Optional<Patient> findBySyntheticId(String syntheticId);

    List<Patient> findTop50ByFamilyNameStartingWithIgnoreCaseOrderByFamilyNameAscGivenNameAsc(String prefix);
}
