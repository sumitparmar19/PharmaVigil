// The one place where a report is accepted, whatever channel it came through. E2B(R3) messages are parsed
// with the hardened reader and checked against the official schema set when it is installed; every accepted
// report gets a receipt and exactly one report.received outbox event in the same transaction. An identical
// resend returns the original receipt and writes no event; a changed message for a known report id is
// accepted as a follow-up.
package io.pharmavigil.intake;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.micrometer.core.instrument.MeterRegistry;
import io.pharmavigil.common.e2b.E2bReader;
import io.pharmavigil.common.e2b.E2bValidator;
import io.pharmavigil.common.e2b.IcsrCase;
import io.pharmavigil.common.e2b.IcsrMapping;
import io.pharmavigil.common.events.ReportReceived;
import io.pharmavigil.intake.outbox.OutboxEvent;
import io.pharmavigil.intake.outbox.OutboxEventRepository;
import io.pharmavigil.intake.receipts.IntakeReceipt;
import io.pharmavigil.intake.receipts.IntakeReceipt.Source;
import io.pharmavigil.intake.receipts.IntakeReceiptRepository;
import tools.jackson.databind.json.JsonMapper;

@Service
public class IntakeService {

    /** Result of one accepted report. duplicate: an identical earlier submission, no new event was written. */
    public record Accepted(IntakeReceipt receipt, boolean duplicate, List<String> warnings) {
    }

    /** The submission cannot be accepted; reasons are safe to show to the sender. */
    public static class RejectedException extends RuntimeException {
        private final List<String> reasons;

        public RejectedException(List<String> reasons) {
            super(String.join("; ", reasons));
            this.reasons = List.copyOf(reasons);
        }

        public List<String> reasons() {
            return reasons;
        }
    }

    static final int MAX_REASONS = 5;
    static final String NOT_SCHEMA_CHECKED =
            "the official E2B(R3) schema set is not installed here, so this message was not schema-validated";

    private final IntakeReceiptRepository receipts;
    private final OutboxEventRepository outbox;
    private final E2bReader reader;
    private final E2bValidator validator;
    private final JsonMapper json;
    private final Clock clock;
    private final MeterRegistry registry;

    public IntakeService(IntakeReceiptRepository receipts, OutboxEventRepository outbox, E2bReader reader,
            E2bValidator validator, JsonMapper json, Clock clock, MeterRegistry registry) {
        this.registry = registry;
        this.receipts = receipts;
        this.outbox = outbox;
        this.reader = reader;
        this.validator = validator;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Accept an E2B(R3) batch. With singleMessage (SOAP SubmitCase) the batch must hold exactly one ICSR;
     * file imports may carry several, accepted together or not at all.
     */
    @Transactional
    public List<Accepted> submitE2b(String xml, Source source, String partnerReference, String submittedBy,
            boolean singleMessage) {
        List<IcsrCase> icsrs;
        try {
            icsrs = reader.read(xml);
        } catch (E2bReader.E2bFormatException e) {
            throw new RejectedException(List.of(e.getMessage()));
        }
        if (singleMessage && icsrs.size() != 1) {
            throw new RejectedException(List.of("send one ICSR per SubmitCase; this batch holds " + icsrs.size()));
        }
        List<String> warnings = new ArrayList<>();
        E2bValidator.Result check = validator.validate(xml);
        switch (check.outcome()) {
            case INVALID -> throw new RejectedException(check.errors().stream().limit(MAX_REASONS)
                    .map(e -> "E2B(R3) schema: " + e).toList());
            case SCHEMA_UNAVAILABLE -> warnings.add(NOT_SCHEMA_CHECKED);
            case VALID -> { }
        }
        List<Accepted> out = new ArrayList<>();
        for (IcsrCase c : icsrs) {
            if (c.safetyReportId() == null || c.safetyReportId().isBlank()) {
                throw new RejectedException(List.of("C.1.1 sender's safety report unique identifier is missing"));
            }
            if (c.safetyReportId().length() > 100) {
                throw new RejectedException(List.of("C.1.1 safety report identifier is longer than 100 characters"));
            }
            String eventId = UUID.randomUUID().toString();
            ReportReceived event = IcsrMapping.toReport(c, source.name(), eventId, clock.instant());
            // Each ICSR of a batch is hashed with the whole message, so a changed batch is a follow-up for all.
            out.add(accept(source, c.safetyReportId(), partnerReference, submittedBy, sha256(xml),
                    check.outcome() == E2bValidator.Outcome.VALID, event, warnings));
        }
        return out;
    }

    /** Accept a pharmacy report already in the canonical shape (eventId and receivedAt are set here). */
    @Transactional
    public Accepted submitReport(ReportReceived report, String submittedBy) {
        String eventId = UUID.randomUUID().toString();
        ReportReceived event = new ReportReceived(eventId, Source.PHARMACY.name(), report.sourceReportId(),
                clock.instant(), report.patientAgeYears(), report.patientSex(), report.reporterType(),
                report.country(), report.onsetDate(), report.narrative(), report.drugs(), report.reactionTerms(),
                report.seriousCriteria());
        // The hash covers the reported content only, not the ids and times set here.
        String content = json.writeValueAsString(new ReportReceived(null, null, report.sourceReportId(), null,
                report.patientAgeYears(), report.patientSex(), report.reporterType(), report.country(),
                report.onsetDate(), report.narrative(), report.drugs(), report.reactionTerms(),
                report.seriousCriteria()));
        return accept(Source.PHARMACY, report.sourceReportId(), null, submittedBy, sha256(content), false, event,
                List.of());
    }

    @Transactional(readOnly = true)
    public IntakeReceipt latest(Source source, String sourceReportId) {
        return receipts.findFirstBySourceAndSourceReportIdOrderByIdDesc(source, sourceReportId).orElse(null);
    }

    private Accepted accept(Source source, String reportId, String partnerReference, String submittedBy,
            String hash, boolean schemaChecked, ReportReceived event, List<String> warnings) {
        var same = receipts.findBySourceAndSourceReportIdAndPayloadSha256(source, reportId, hash);
        if (same.isPresent()) {
            countAfterCommit(source, "duplicate");
            return new Accepted(same.get(), true, warnings);
        }
        List<String> notes = new ArrayList<>(warnings);
        boolean followUp = receipts.existsBySourceAndSourceReportId(source, reportId);
        if (followUp) {
            notes.add("follow-up to an earlier report with the same identifier; it becomes a new case version");
        }
        IntakeReceipt receipt = receipts.save(new IntakeReceipt(UUID.randomUUID().toString(), source, reportId,
                partnerReference, submittedBy, hash, followUp, schemaChecked, clock.instant()));
        outbox.save(new OutboxEvent(event.eventId(), "intake_receipt", receipt.getReceiptId(), ReportReceived.TOPIC,
                json.writeValueAsString(event), clock.instant()));
        countAfterCommit(source, followUp ? "follow_up" : "new");
        return new Accepted(receipt, false, notes);
    }

    /** pv_reports_received_total{source, kind}: counted only when the receipt is committed. */
    private void countAfterCommit(Source source, String kind) {
        Runnable count = () -> registry.counter("pv.reports.received", "source", source.name(), "kind", kind)
                .increment();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    count.run();
                }
            });
        } else {
            count.run();
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
