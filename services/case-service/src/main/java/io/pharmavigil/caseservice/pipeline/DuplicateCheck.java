// Stage 1 of the pipeline: compares a new or changed case with other cases that share a drug (received in the
// last year, up to 50) using the duplicate model, and flags the best match above the threshold as a possible
// duplicate. A flag is a suggestion for the reviewer; cases are never merged automatically. Without the model
// the step is skipped and the audit trail says so.
package io.pharmavigil.caseservice.pipeline;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import io.pharmavigil.caseservice.cases.CaseService;
import io.pharmavigil.caseservice.model.CaseVersion;
import io.pharmavigil.caseservice.model.CaseVersionRepository;
import io.pharmavigil.caseservice.model.SafetyCase;
import io.pharmavigil.caseservice.models.AgeBands;
import io.pharmavigil.caseservice.models.DuplicateModel;
import io.pharmavigil.caseservice.models.ModelBundle;
import io.pharmavigil.caseservice.models.SeriousnessModel;

@Component
public class DuplicateCheck {

    private static final Logger log = LoggerFactory.getLogger(DuplicateCheck.class);
    static final int MAX_CANDIDATES = 50;

    public record Match(Long otherCaseId, double score) {
    }

    private final ModelBundle models;
    private final CaseService cases;
    private final CaseVersionRepository versions;

    public DuplicateCheck(ModelBundle models, CaseService cases, CaseVersionRepository versions) {
        this.models = models;
        this.cases = cases;
        this.versions = versions;
    }

    /** Runs inside the stage transaction; returns the flagged match, if any. */
    public Match check(SafetyCase c) {
        var model = models.duplicates();
        if (model.isEmpty()) {
            cases.note(c.getId(), "pipeline", "DUPLICATE_CHECK", "skipped: duplicate model unavailable");
            return null;
        }
        CaseVersion v = cases.latest(c);
        Set<String> rx = new HashSet<>();
        Set<String> names = new HashSet<>();
        v.getDrugs().forEach(d -> {
            if (d.getRxcui() != null) {
                rx.add(d.getRxcui());
            }
            names.add(d.getDrugName().toLowerCase(Locale.ROOT));
        });
        List<Long> candidates = versions.findDuplicateCandidates(c.getId(), c.getReceivedDate().minusDays(365),
                rx.isEmpty() ? Set.of("-") : rx, names.isEmpty() ? Set.of("-") : names, Limit.of(MAX_CANDIDATES));
        DuplicateModel.Profile mine = profile(c, v);
        Match best = null;
        try {
            for (Long otherId : candidates) {
                SafetyCase other = cases.load(otherId);
                double score = model.get().score(DuplicateModel.features(mine, profile(other, cases.latest(other))));
                if (best == null || score > best.score()) {
                    best = new Match(otherId, score);
                }
            }
        } catch (Exception e) {
            log.warn("duplicate check failed for case {}: {}", c.getId(), e.toString());
            cases.note(c.getId(), "pipeline", "DUPLICATE_CHECK", "skipped: model error");
            return null;
        }
        String actor = "model:" + model.get().info().name() + "@" + model.get().info().version();
        if (best != null && best.score() >= model.get().threshold()) {
            cases.markDuplicate(c.getId(), best.otherCaseId(), score5(best.score()), actor);
            return best;
        }
        cases.note(c.getId(), actor, "DUPLICATE_CHECK", candidates.size() + " candidates, none above "
                + model.get().threshold());
        return null;
    }

    static DuplicateModel.Profile profile(SafetyCase c, CaseVersion v) {
        Set<String> drugs = new HashSet<>();
        v.getDrugs().forEach(d -> drugs.add(d.getRxcui() != null ? d.getRxcui() : d.getDrugName().toLowerCase(Locale.ROOT)));
        Set<String> pts = new HashSet<>();
        v.getReactions().forEach(r -> {
            if (r.getPt() != null) {
                pts.add(r.getPt().toLowerCase(Locale.ROOT));
            } else if (r.getReportedTerm() != null) {
                pts.add(r.getReportedTerm().toLowerCase(Locale.ROOT));
            }
        });
        LocalDate onset = v.getOnsetDate();
        return new DuplicateModel.Profile(drugs, pts, v.getPatientSex(), AgeBands.of(v.getPatientAge()), v.getCountry(),
                SeriousnessModel.occupation(v.getReporterType()), onset, c.getReceivedDate());
    }

    static java.math.BigDecimal score5(double s) {
        return java.math.BigDecimal.valueOf(s).setScale(5, java.math.RoundingMode.HALF_UP);
    }
}
