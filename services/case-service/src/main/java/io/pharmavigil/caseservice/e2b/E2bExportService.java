// Approved case → ICH E2B(R3) ICSR. The export maps the latest case version and the approved narrative, then
// validates the XML against the official schema set; only a VALID export may be queued for submission
// (engineering rule 7). MedDRA codes stay empty until the MedDRA licence is active: reactions carry the
// reported term and the PT text (docs/E2B_R3.md).
package io.pharmavigil.caseservice.e2b;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.pharmavigil.caseservice.cases.CaseService;
import io.pharmavigil.caseservice.model.CaseEnums.Source;
import io.pharmavigil.caseservice.model.CaseEnums.Status;
import io.pharmavigil.caseservice.model.CaseVersion;
import io.pharmavigil.caseservice.model.Narrative;
import io.pharmavigil.caseservice.model.NarrativeRepository;
import io.pharmavigil.caseservice.model.SafetyCase;
import io.pharmavigil.common.e2b.E2bValidator;
import io.pharmavigil.common.e2b.E2bWriter;
import io.pharmavigil.common.e2b.IcsrCase;
import io.pharmavigil.common.e2b.IcsrMapping;
import io.pharmavigil.common.web.ConflictException;

@Service
public class E2bExportService {

    /** The XML and its schema check. queueable() is the only gate the submission step uses. */
    public record Export(String safetyReportId, String xml, E2bValidator.Result validation) {
        public boolean queueable() {
            return validation.valid();
        }
    }

    private static final Set<Status> EXPORTABLE = EnumSet.of(Status.APPROVED, Status.SUBMITTED, Status.ACKNOWLEDGED);

    private final CaseService cases;
    private final NarrativeRepository narratives;
    private final E2bValidator validator;
    private final Clock clock;
    private final String senderId;
    private final String receiverId;

    public E2bExportService(CaseService cases, NarrativeRepository narratives, E2bValidator validator, Clock clock,
            @Value("${pv.e2b.sender-id:PHARMAVIGIL}") String senderId,
            @Value("${pv.e2b.receiver-id:MOCK-REGULATOR}") String receiverId) {
        this.cases = cases;
        this.narratives = narratives;
        this.validator = validator;
        this.clock = clock;
        this.senderId = senderId;
        this.receiverId = receiverId;
    }

    @Transactional(readOnly = true)
    public Export export(Long caseId) {
        SafetyCase c = cases.load(caseId);
        if (!EXPORTABLE.contains(c.getStatus())) {
            throw new ConflictException("Only approved cases can be exported; case " + c.getCaseNumber() + " is "
                    + c.getStatus() + ".");
        }
        Narrative narrative = narratives.findByCaseIdOrderByIdAsc(caseId).stream()
                .filter(n -> n.getApprovedAt() != null)
                .max(Comparator.comparing(Narrative::getApprovedAt))
                .orElseThrow(() -> new ConflictException("Approve a narrative before exporting case "
                        + c.getCaseNumber() + "."));
        IcsrCase icsr = toIcsr(c, cases.latest(c), narrative.getBody());
        String xml = new E2bWriter().write(icsr);
        return new Export(icsr.safetyReportId(), xml, validator.validate(xml));
    }

    IcsrCase toIcsr(SafetyCase c, CaseVersion v, String narrative) {
        String country = v.getCountry() == null ? "US" : v.getCountry();
        String safetyReportId = country + "-" + senderId + "-" + c.getCaseNumber();
        // The worldwide case id stays the one the first sender gave the case.
        String worldwideId = c.getSource() == Source.PHARMACY ? safetyReportId : c.getSourceReportId();
        String version = c.getCaseNumber() + "-V" + v.getVersionNo();
        List<String> criteria = v.getSeriousCriteria() == null ? List.of()
                : Arrays.stream(v.getSeriousCriteria().split(",")).map(String::strip).toList();
        List<IcsrCase.Reaction> reactions = v.getReactions().stream()
                .map(r -> new IcsrCase.Reaction(r.getReportedTerm(), r.getPt(), null, null, v.getOnsetDate(),
                        criteria.contains(IcsrMapping.DEATH), criteria.contains(IcsrMapping.LIFE_THREATENING),
                        criteria.contains(IcsrMapping.HOSPITALIZATION), criteria.contains(IcsrMapping.DISABILITY),
                        criteria.contains(IcsrMapping.CONGENITAL_ANOMALY),
                        criteria.contains(IcsrMapping.OTHER_MEDICALLY_IMPORTANT)))
                .toList();
        List<IcsrCase.Drug> drugs = v.getDrugs().stream()
                .map(d -> new IcsrCase.Drug(IcsrMapping.characterization(d.getRole()), d.getDrugName(), d.getRxcui(),
                        d.getDoseText(), d.getRoute()))
                .toList();
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).withNano(0);
        LocalDate mostRecent = v.getCreatedAt().atOffset(ZoneOffset.UTC).toLocalDate();
        return new IcsrCase("B-" + version, "M-" + version, senderId, receiverId, now, safetyReportId, worldwideId, 1,
                c.getReceivedDate(), mostRecent, IcsrMapping.qualification(v.getReporterType()), country,
                v.getPatientAge(), IcsrMapping.sexCode(v.getPatientSex()), reactions, drugs, narrative);
    }
}
