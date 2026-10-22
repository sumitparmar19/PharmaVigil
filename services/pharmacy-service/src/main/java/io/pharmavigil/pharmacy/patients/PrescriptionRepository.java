// Prescriptions; `lockById` row-locks a prescription while it is being filled.
package io.pharmavigil.pharmacy.patients;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Prescription p where p.id = :id")
    Optional<Prescription> lockById(@Param("id") Long id);

    List<Prescription> findByPatientIdOrderByWrittenDateDesc(Long patientId);
}
