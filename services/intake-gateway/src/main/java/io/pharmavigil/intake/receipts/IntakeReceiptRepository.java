// Receipt lookups: identical resends (same payload hash), follow-ups and status queries by report id.
package io.pharmavigil.intake.receipts;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IntakeReceiptRepository extends JpaRepository<IntakeReceipt, Long> {

    Optional<IntakeReceipt> findBySourceAndSourceReportIdAndPayloadSha256(IntakeReceipt.Source source,
            String sourceReportId, String payloadSha256);

    boolean existsBySourceAndSourceReportId(IntakeReceipt.Source source, String sourceReportId);

    Optional<IntakeReceipt> findFirstBySourceAndSourceReportIdOrderByIdDesc(IntakeReceipt.Source source,
            String sourceReportId);

    Optional<IntakeReceipt> findByReceiptId(String receiptId);
}
