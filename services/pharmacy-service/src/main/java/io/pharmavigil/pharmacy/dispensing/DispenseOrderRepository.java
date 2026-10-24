// Dispense orders (a patient's own history is readable with the PATIENT role).
package io.pharmavigil.pharmacy.dispensing;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DispenseOrderRepository extends JpaRepository<DispenseOrder, Long> {

    @Query("select o from DispenseOrder o join fetch o.drug where o.patient.id = :patientId order by o.createdAt desc")
    List<DispenseOrder> findByPatientIdOrderByCreatedAtDesc(@Param("patientId") Long patientId);
}
