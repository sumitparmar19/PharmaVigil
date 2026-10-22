// Stage 3: seriousness triage. Seriousness criteria stated by the reporter (E2B E.i.3.2) decide on their own.
// Otherwise the model scores what was known before any outcome was coded (outcome-revealing PTs are dropped
// inside the model's feature builder); without the model the case stays UNKNOWN, which the reviewer queue
// ranks above non-serious so nothing serious waits at the back.
package io.pharmavigil.caseservice.pipeline;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.pharmavigil.caseservice.cases.CaseService;
import io.pharmavigil.caseservice.model.CaseEnums.Seriousness;
import io.pharmavigil.caseservice.model.CaseVersion;
import io.pharmavigil.caseservice.model.SafetyCase;
import io.pharmavigil.caseservice.models.ModelBundle;
import io.pharmavigil.caseservice.models.SeriousnessModel;

@Component
public class Triage {

    private static final Logger log = LoggerFactory.getLogger(Triage.class);

    private final ModelBundle models;
    private final CaseService cases;
    private final double threshold;

    public Triage(ModelBundle models, CaseService cases, @Value("${pv.triage.serious-threshold:0.5}") double threshold) {
        this.models = models;
        this.cases = cases;
        this.threshold = threshold;
    }

    public Seriousness triage(SafetyCase c) {
        CaseVersion v = cases.latest(c);
        if (v.getSeriousCriteria() != null && !v.getSeriousCriteria().isBlank()) {
            cases.setSeriousness(c.getId(), Seriousness.SERIOUS, null, "pipeline");
            cases.note(c.getId(), "pipeline", "TRIAGE", "serious by reported criteria: " + v.getSeriousCriteria());
            return Seriousness.SERIOUS;
        }
        var model = models.seriousness();
        if (model.isEmpty()) {
            cases.note(c.getId(), "pipeline", "TRIAGE", "seriousness model unavailable: stays UNKNOWN");
            return Seriousness.UNKNOWN;
        }
        List<SeriousnessModel.Drug> drugs = new ArrayList<>();
        v.getDrugs().forEach(d -> drugs.add(new SeriousnessModel.Drug(d.getRxcui(), d.getRole())));
        List<String> pts = new ArrayList<>();
        v.getReactions().forEach(r -> {
            if (r.getPt() != null) {
                pts.add(r.getPt());
            }
        });
        double p;
        try {
            p = model.get().predict(new SeriousnessModel.Input(v.getPatientAge(), v.getPatientSex(),
                    v.getReporterType(), v.getCountry(), drugs, pts));
        } catch (Exception e) {
            log.warn("seriousness model failed for case {}: {}", c.getId(), e.toString());
            cases.note(c.getId(), "pipeline", "TRIAGE", "seriousness model error: stays UNKNOWN");
            return Seriousness.UNKNOWN;
        }
        Seriousness s = p >= threshold ? Seriousness.SERIOUS : Seriousness.NON_SERIOUS;
        cases.setSeriousness(c.getId(), s, DuplicateCheck.score5(p),
                "model:" + model.get().info().name() + "@" + model.get().info().version());
        return s;
    }
}
