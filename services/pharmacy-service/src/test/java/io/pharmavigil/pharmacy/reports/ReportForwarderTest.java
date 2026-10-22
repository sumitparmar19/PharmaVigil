// Report forwarding to intake-gateway (a local stand-in): pending reports go out oldest first with
// de-identified facts only and become SUBMITTED with the receipt id; while intake is down they stay pending;
// a refused report is marked FAILED instead of being retried forever.
package io.pharmavigil.pharmacy.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

import io.pharmavigil.pharmacy.DbTest;
import io.pharmavigil.pharmacy.catalog.Drug;
import io.pharmavigil.pharmacy.patients.Patient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ReportForwarderTest extends DbTest {

    @Autowired SideEffectReportRepository reportRepo;
    @Autowired ReportService reportService;
    @Autowired TransactionTemplate tx;

    HttpServer server;
    final AtomicInteger status = new AtomicInteger(202);
    final List<JsonNode> bodies = new ArrayList<>();
    final List<String> auth = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        // Earlier tests may have left pending reports in the shared database; this test owns the queue.
        tx.executeWithoutResult(s -> reportRepo.findTop50ByStatusOrderByIdAsc(
                SideEffectReport.Status.PENDING_SUBMISSION).forEach(r -> r.markFailed()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/intake/reports", ex -> {
            bodies.add(JsonMapper.builder().build().readTree(
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = ("{\"receiptId\":\"rcpt-" + bodies.size() + "\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status.get(), body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ReportForwarder forwarder(String url) {
        return new ReportForwarder(reportRepo, tokens, tx, RestClient.builder(), url, Duration.ofSeconds(2), true);
    }

    private SideEffectReport report(LocalDate birth, String sex) {
        Patient p = patients.save(new Patient(unique("syn"), "Test", unique("Fam"), birth, sex));
        Drug d = drug(true);
        return reportService.create(p.getId(), d.getId(), "Dizzy after the second dose.", LocalDate.of(2026, 9, 30),
                "pharmacist-1");
    }

    @Test
    void pendingReportsAreDeliveredWithDeidentifiedFacts() {
        SideEffectReport first = report(LocalDate.of(1970, 10, 1), "F");
        SideEffectReport second = report(LocalDate.of(1990, 1, 1), "U");
        int sent = forwarder("http://127.0.0.1:" + server.getAddress().getPort()).forwardPending();
        assertThat(sent).isEqualTo(2);
        JsonNode b = bodies.get(0);
        assertThat(b.path("pharmacyReportId").asString()).isEqualTo("PH-" + first.getId());
        assertThat(b.path("patientAgeYears").asInt()).isEqualTo(55);
        assertThat(b.path("patientSex").asString()).isEqualTo("F");
        assertThat(b.path("drugs").get(0).path("role").asString()).isEqualTo("PS");
        assertThat(b.toString()).doesNotContain("Fam-").doesNotContain("syn-").doesNotContain("1970");
        assertThat(bodies.get(1).path("patientSex").asString()).isEqualTo("UNK");
        assertThat(auth.get(0)).startsWith("Bearer ");
        SideEffectReport after = reportRepo.findById(first.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SideEffectReport.Status.SUBMITTED);
        assertThat(after.getExternalReportId()).isEqualTo("rcpt-1");
        assertThat(forwarder("http://127.0.0.1:" + server.getAddress().getPort()).forwardPending()).isZero();
    }

    @Test
    void intakeDownKeepsReportsPendingAndRefusedReportsFail() {
        SideEffectReport r = report(LocalDate.of(1980, 5, 5), "M");
        assertThat(forwarder("http://127.0.0.1:9").forwardPending()).isZero();
        assertThat(reportRepo.findById(r.getId()).orElseThrow().getStatus())
                .isEqualTo(SideEffectReport.Status.PENDING_SUBMISSION);
        status.set(400);
        assertThat(forwarder("http://127.0.0.1:" + server.getAddress().getPort()).forwardPending()).isZero();
        assertThat(reportRepo.findById(r.getId()).orElseThrow().getStatus()).isEqualTo(SideEffectReport.Status.FAILED);
    }
}
