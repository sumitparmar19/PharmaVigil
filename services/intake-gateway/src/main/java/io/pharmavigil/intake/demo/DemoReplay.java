// Demo mode only (pv.demo.enabled=true): the "Run the pipeline" button. Replays a batch of real, de-identified
// FAERS reports from the demo slice (data/pharmavigil_data/demo_slice.py) through the real intake paths: each
// report becomes an ICH E2B(R3) message; three in four go to the partner SOAP endpoint over HTTP with
// WS-Security, one in four to the REST E2B intake. From there the normal Kafka pipeline takes over. Every
// replayed report gets the safety report id DEMO-<run>-<FAERS primaryid>, which is how case-service counts a
// run's progress and how its demo auto-review finds the cases. One run at a time per account, a cooldown after
// each, and a small global limit, so a public demo cannot be used to flood the system.
package io.pharmavigil.intake.demo;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.stereotype.Service;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.client.support.interceptor.ClientInterceptor;
import org.springframework.ws.soap.security.wss4j2.Wss4jSecurityInterceptor;

import io.pharmavigil.common.e2b.E2bWriter;
import io.pharmavigil.common.e2b.IcsrCase;
import io.pharmavigil.common.web.ConflictException;
import io.pharmavigil.intake.IntakeService;
import io.pharmavigil.intake.receipts.IntakeReceipt;
import io.pharmavigil.intake.soap.generated.SubmitCaseRequest;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "pv.demo.enabled", havingValue = "true")
public class DemoReplay {

    private static final Logger log = LoggerFactory.getLogger(DemoReplay.class);
    public static final int MAX_REPORTS = 200;
    private static final int MAX_CONCURRENT_RUNS = 2;
    private static final int KEPT_RUNS = 50;
    private static final Map<String, Integer> QUALIFICATION = Map.of("PHYSICIAN", 1, "PHARMACIST", 2,
            "OTHER_HCP", 3, "LAWYER", 4, "CONSUMER", 5);
    private static final Map<String, Integer> CHARACTERIZATION = Map.of("PS", 1, "SS", 1, "C", 2, "I", 3);

    /** One line of replay_reports.jsonl. */
    public record SliceReport(String faers_primaryid, String faers_caseid, String quarter, String fda_date,
            String event_date, Double age_years, String sex, String reporter_type, String country,
            List<String> outcomes, List<SliceDrug> drugs, List<String> reactions) {
    }

    public record SliceDrug(String name, String role, String route, String dose) {
    }

    /** What the demo page shows about a run while intake sends it. */
    public record RunView(String runId, String account, Instant startedAt, Instant finishedAt, int size,
            int sent, int accepted, int rejected, int viaSoap, int viaRest, List<String> errors) {
    }

    private static final class Run {
        final String id;
        final String account;
        final Instant startedAt;
        final int size;
        final AtomicInteger sent = new AtomicInteger();
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        final AtomicInteger viaSoap = new AtomicInteger();
        final AtomicInteger viaRest = new AtomicInteger();
        final List<String> errors = Collections.synchronizedList(new ArrayList<>());
        volatile Instant finishedAt;

        Run(String id, String account, Instant startedAt, int size) {
            this.id = id;
            this.account = account;
            this.startedAt = startedAt;
            this.size = size;
        }

        RunView view() {
            return new RunView(id, account, startedAt, finishedAt, size, sent.get(), accepted.get(), rejected.get(),
                    viaSoap.get(), viaRest.get(), List.copyOf(errors));
        }
    }

    private final IntakeService intake;
    private final E2bWriter writer = new E2bWriter();
    private final Clock clock;
    private final Duration cooldown;
    private final long pauseMs;
    private final String soapUrl;
    private final org.springframework.core.env.Environment env;
    private final String partnerUser;
    private final String partnerPassword;
    private final List<SliceReport> slice;
    private final Map<String, Run> runs = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Run> e) {
            return size() > KEPT_RUNS;
        }
    });
    private final Map<String, Instant> lastStart = new ConcurrentHashMap<>();
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_RUNS);
    private final SecureRandom ids = new SecureRandom();
    private volatile WebServiceTemplate soap;

    public DemoReplay(IntakeService intake, Clock clock, JsonMapper json,
            @Value("${pv.demo.slice-file:}") String sliceFile,
            @Value("${pv.demo.cooldown-seconds:120}") long cooldownSeconds,
            @Value("${pv.demo.pause-ms:40}") long pauseMs,
            @Value("${pv.demo.soap-url:}") String soapUrl, org.springframework.core.env.Environment env,
            @Value("${pv.soap.partner-user:}") String partnerUser,
            @Value("${pv.soap.partner-password:}") String partnerPassword) {
        this.intake = intake;
        this.clock = clock;
        this.cooldown = Duration.ofSeconds(cooldownSeconds);
        this.pauseMs = pauseMs;
        this.soapUrl = soapUrl;
        this.env = env;
        this.partnerUser = partnerUser;
        this.partnerPassword = partnerPassword;
        this.slice = load(json, sliceFile);
    }

    private static List<SliceReport> load(JsonMapper json, String file) {
        if (file == null || file.isBlank() || !Files.isReadable(Path.of(file))) {
            log.warn("demo replay: no slice file (pv.demo.slice-file={}); the run button stays disabled", file);
            return List.of();
        }
        try (var lines = Files.lines(Path.of(file))) {
            List<SliceReport> out = lines.filter(l -> !l.isBlank())
                    .map(l -> json.readValue(l, SliceReport.class))
                    .filter(r -> r.reactions() != null && !r.reactions().isEmpty()
                            && r.drugs() != null && !r.drugs().isEmpty()
                            && QUALIFICATION.containsKey(r.reporter_type()))
                    .toList();
            log.info("demo replay: {} FAERS reports loaded from {}", out.size(), file);
            return out;
        } catch (IOException | RuntimeException e) {
            log.warn("demo replay: slice file {} unreadable ({}); the run button stays disabled", file, e.toString());
            return List.of();
        }
    }

    public int available() {
        return slice.size();
    }

    public RunView start(String account, int requested) {
        if (slice.isEmpty()) {
            throw new ConflictException("The demo data is not loaded on this server.");
        }
        int size = Math.max(1, Math.min(requested, Math.min(MAX_REPORTS, slice.size())));
        Instant now = clock.instant();
        if (active.contains(account)) {
            throw new ConflictException("Your previous run is still sending reports.");
        }
        Instant last = lastStart.get(account);
        if (last != null && last.plus(cooldown).isAfter(now)) {
            long wait = Duration.between(now, last.plus(cooldown)).toSeconds() + 1;
            throw new ConflictException("Please wait " + wait + " s before starting another run.");
        }
        synchronized (active) {
            if (active.size() >= MAX_CONCURRENT_RUNS) {
                throw new ConflictException("Other visitors are running the pipeline; try again in a minute.");
            }
            active.add(account);
        }
        lastStart.put(account, now);
        Run run = new Run(newRunId(), account, now, size);
        runs.put(run.id, run);
        List<SliceReport> picked = pick(size);
        executor.submit(() -> send(run, picked));
        return run.view();
    }

    public RunView get(String runId) {
        Run r = runs.get(runId);
        return r == null ? null : r.view();
    }

    private String newRunId() {
        String alphabet = "abcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder b = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            b.append(alphabet.charAt(ids.nextInt(alphabet.length())));
        }
        return b.toString();
    }

    private List<SliceReport> pick(int size) {
        List<SliceReport> copy = new ArrayList<>(slice);
        Collections.shuffle(copy, new Random(ids.nextLong()));
        return copy.subList(0, size);
    }

    private void send(Run run, List<SliceReport> reports) {
        try {
            for (int i = 0; i < reports.size(); i++) {
                SliceReport r = reports.get(i);
                String id = DemoIds.safetyReportId(run.id, r.faers_primaryid());
                String xml = writer.write(toIcsr(run.id, id, r));
                try {
                    if (i % 4 == 3) {
                        intake.submitE2b(xml, IntakeReceipt.Source.E2B_IMPORT, null, run.account, true);
                        run.viaRest.incrementAndGet();
                    } else {
                        SubmitCaseRequest req = new SubmitCaseRequest();
                        req.setPartnerReference("FAERS-REPLAY-" + run.id);
                        req.setE2BMessage(xml);
                        soapClient().marshalSendAndReceive(req);
                        run.viaSoap.incrementAndGet();
                    }
                    run.accepted.incrementAndGet();
                } catch (RuntimeException e) {
                    run.rejected.incrementAndGet();
                    if (run.errors.size() < 5) {
                        run.errors.add(id + ": " + e.getMessage());
                    }
                }
                run.sent.incrementAndGet();
                if (pauseMs > 0) {
                    Thread.sleep(pauseMs);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            run.finishedAt = clock.instant();
            active.remove(run.account);
        }
    }

    IcsrCase toIcsr(String runId, String safetyReportId, SliceReport r) {
        LocalDate received = parse(r.fda_date(), LocalDate.now(clock));
        LocalDate onset = parse(r.event_date(), null);
        Set<String> outc = r.outcomes() == null ? Set.of() : Set.copyOf(r.outcomes());
        boolean other = outc.contains("OT") || outc.contains("RI");
        List<IcsrCase.Reaction> reactions = r.reactions().stream()
                .map(pt -> new IcsrCase.Reaction(pt, null, null, null, onset, outc.contains("DE"), outc.contains("LT"),
                        outc.contains("HO"), outc.contains("DS"), outc.contains("CA"), other))
                .toList();
        List<IcsrCase.Drug> drugs = r.drugs().stream()
                .map(d -> new IcsrCase.Drug(CHARACTERIZATION.getOrDefault(d.role(), 2), d.name(), null, d.dose(),
                        d.route()))
                .toList();
        Integer sex = "M".equals(r.sex()) ? Integer.valueOf(1) : "F".equals(r.sex()) ? Integer.valueOf(2) : null;
        return new IcsrCase("DEMO-" + runId, safetyReportId, "FAERS-REPLAY", "PHARMAVIGIL",
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC), safetyReportId,
                "FAERS-" + r.faers_caseid(), 1, received, received, QUALIFICATION.get(r.reporter_type()),
                r.country(), r.age_years() == null ? null : BigDecimal.valueOf(r.age_years()), sex, reactions, drugs,
                null);
    }

    private static LocalDate parse(String day, LocalDate fallback) {
        try {
            return day == null ? fallback : LocalDate.parse(day);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private WebServiceTemplate soapClient() {
        WebServiceTemplate t = soap;
        if (t != null) {
            return t;
        }
        try {
            Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
            marshaller.setContextPath("io.pharmavigil.intake.soap.generated");
            marshaller.afterPropertiesSet();
            WebServiceTemplate client = new WebServiceTemplate(marshaller);
            // Our own SOAP endpoint over loopback HTTP, so replayed reports pass WS-Security like a partner's.
            client.setDefaultUri(soapUrl.isBlank() ? "http://localhost:" + env.getProperty("local.server.port",
                    env.getProperty("server.port", "8082")) + "/ws" : soapUrl);
            Wss4jSecurityInterceptor security = new Wss4jSecurityInterceptor();
            security.setSecurementActions("Timestamp UsernameToken");
            security.setSecurementUsername(partnerUser);
            security.setSecurementPassword(partnerPassword);
            security.setSecurementPasswordType("PasswordDigest");
            security.setSecurementUsernameTokenNonce(true);
            security.setSecurementUsernameTokenCreated(true);
            security.setValidateResponse(false);
            security.afterPropertiesSet();
            client.setInterceptors(new ClientInterceptor[] {security});
            soap = client;
            return client;
        } catch (Exception e) {
            throw new IllegalStateException("SOAP client setup failed: " + e.getMessage(), e);
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
