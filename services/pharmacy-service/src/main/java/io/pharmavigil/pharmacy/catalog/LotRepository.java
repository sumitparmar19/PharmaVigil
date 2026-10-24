// Lot queries. `lockDispensable` takes row locks (SELECT … FOR UPDATE on Oracle, UPDLOCK on SQL Server)
// on the drug's in-date, non-empty lots in FEFO order, so concurrent dispenses queue instead of overselling.
package io.pharmavigil.pharmacy.catalog;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface LotRepository extends JpaRepository<Lot, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Lot l where l.drug.id = :drugId and l.expiryDate > :today and l.quantity > 0 "
            + "order by l.expiryDate asc, l.id asc")
    List<Lot> lockDispensable(@Param("drugId") Long drugId, @Param("today") LocalDate today);

    List<Lot> findByDrugIdOrderByExpiryDateAsc(Long drugId);
}
