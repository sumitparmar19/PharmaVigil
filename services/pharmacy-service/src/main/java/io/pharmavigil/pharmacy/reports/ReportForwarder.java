// Delivers stored side-effect reports to intake-gateway (POST /api/intake/reports). The report row is written
// first (in the request's transaction); this relay sends PENDING_SUBMISSION rows in id order, so a report is
// never lost when the safety platform is down: it stays pending and is retried. Only de-identified facts leave
// the pharmacy: age at onset in whole years (computed here from the synthetic birth date), sex, the drug's RxCUI
// and name, the reported text and dates. A 4xx answer means the report itself is unacceptable: it is marked FAILED
// for a person to look at instead of being retried forever.
package io.pharmavigil.pharmacy.reports;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import io.pharmavigil.common.security.Roles;
import io.pharmavigil.common.security.TokenService;

@Component
public class ReportForwarder {

    public record DrugOut(String rxcui, String name, String role, String doseText) {
    }

    public record ReportOut(String pharmacyReportId, BigDecimal patientAgeYears, String patientSex, String country,
            LocalDate onsetDate, String narrative, List<DrugOut> drugs, List<String> reactionTerms) {
    }

    record Receipt(String receiptId) {
    }

    static final String SERVICE_USER = "system:pharmacy-service";
    private static final Logger log = LoggerFactory.getLogger(ReportForwarder.class);

    private final SideEffectReportRepository reports;
    private final TokenService tokens;
    private final TransactionTemplate tx;
    private final RestClient http;
    private final boolean enabled;

    public ReportForwarder(SideEffectReportRepository reports, TokenService tokens, TransactionTemplate tx,
            RestClient.Builder builder, @Value("${pv.intake.url:http://localhost:8082}") String url,
            @Value("${pv.intake.timeout:PT3S}") Duration timeout,
            @Value("${pv.intake.forward-enabled:true}") boolean enabled) {
        this.reports = reports;
        this.tokens = tokens;
        this.tx = tx;
        this.enabled = enabled;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(timeout);
        rf.setReadTimeout(timeout);
        this.http = builder.baseUrl(url).requestFactory(rf).build();
    }

    @Scheduled(fixedDelayString = "${pv.intake.forward-interval:PT5S}")
    public void scheduled() {
        if (enabled) {
            forwardPending();
        }
    }

    /** Sends pending reports oldest first; stops at the first delivery failure. Returns how many were sent. */
    public int forwardPending() {
        List<Long> ids = tx.execute(s -> reports.findTop50ByStatusOrderByIdAsc(
                SideEffectReport.Status.PENDING_SUBMISSION).stream().map(SideEffectReport::getId).toList());
        int sent = 0;
        for (Long id : ids) {
            ReportOut out = tx.execute(s -> toIntake(reports.findById(id).orElseThrow()));
            String token = tokens.issue(SERVICE_USER, List.of(Roles.PHARMACIST));
            try {
                Receipt r = http.post().uri("/api/intake/reports").header("Authorization", "Bearer " + token)
                        .body(out).retrieve().body(Receipt.class);
                tx.executeWithoutResult(s -> reports.findById(id).orElseThrow()
                        .markSubmitted(r == null ? null : r.receiptId()));
                sent++;
            } catch (HttpClientErrorException e) {
                log.warn("intake refused report {} ({}); marked FAILED for review", id, e.getStatusCode());
                tx.executeWithoutResult(s -> reports.findById(id).orElseThrow().markFailed());
            } catch (RuntimeException e) {
                log.warn("intake-gateway unavailable; report {} stays pending: {}", id, e.toString());
                return sent;
            }
        }
        return sent;
    }

    ReportOut toIntake(SideEffectReport r) {
        LocalDate onset = r.getOnsetDate();
        LocalDate ref = onset != null ? onset : r.getCreatedAt().atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate birth = r.getPatient().getBirthDate();
        BigDecimal age = birth == null || birth.isAfter(ref) ? null
                : BigDecimal.valueOf(Period.between(birth, ref).getYears());
        String sex = "M".equals(r.getPatient().getSex()) || "F".equals(r.getPatient().getSex())
                ? r.getPatient().getSex() : "UNK";
        var d = r.getDrug();
        String dose = d.getStrength();
        return new ReportOut("PH-" + r.getId(), age, sex, "US", onset, r.getNarrative(),
                List.of(new DrugOut(d.getRxcui(), d.getName(), "PS", dose)), List.of());
    }
}
