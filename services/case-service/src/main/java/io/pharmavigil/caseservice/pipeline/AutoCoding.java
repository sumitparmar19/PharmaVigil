// Stage 2: MedDRA coding suggestions. Each reported term that has no code yet gets the coder's best PT with its
// score and the model's name and version; a report without listed terms gets up to five PTs suggested from the
// narrative. The case is CODED when every suggestion reaches the coder's threshold, otherwise (or when the coder
// is unavailable) NEEDS_MANUAL_CODING. Codes already set by a reviewer or an import are never touched.
package io.pharmavigil.caseservice.pipeline;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.pharmavigil.caseservice.cases.CaseService;
import io.pharmavigil.caseservice.model.CaseEnums.CodeSource;
import io.pharmavigil.caseservice.model.CaseEnums.Status;
import io.pharmavigil.caseservice.model.SafetyCase;
import io.pharmavigil.caseservice.model.VersionDraft;
import io.pharmavigil.caseservice.models.MedDraCoder;
import io.pharmavigil.caseservice.models.ModelBundle;

@Component
public class AutoCoding {

    private static final Logger log = LoggerFactory.getLogger(AutoCoding.class);
    static final int NARRATIVE_SUGGESTIONS = 5;

    private final ModelBundle models;
    private final CaseService cases;

    public AutoCoding(ModelBundle models, CaseService cases) {
        this.models = models;
        this.cases = cases;
    }

    /** Runs inside the stage transaction; returns the status the case should move to. */
    public Status code(SafetyCase c) {
        var coder = models.coder();
        if (coder.isEmpty()) {
            cases.note(c.getId(), "pipeline", "CODING", "coder unavailable: needs manual coding");
            return Status.NEEDS_MANUAL_CODING;
        }
        MedDraCoder m = coder.get();
        VersionDraft d = VersionDraft.from(cases.latest(c));
        boolean confident = true;
        boolean changed = false;
        try {
            if (d.reactions.isEmpty()) {
                for (MedDraCoder.Suggestion s : m.suggest(d.reportedText, NARRATIVE_SUGGESTIONS)) {
                    if (s.score() >= m.threshold()) {
                        d.reactions.add(suggested(VersionDraft.NARRATIVE_TERM, s, m));
                        changed = true;
                    }
                }
                confident = changed;
            } else {
                for (int i = 0; i < d.reactions.size(); i++) {
                    VersionDraft.ReactionDraft r = d.reactions.get(i);
                    if (r.codeSource() != CodeSource.NONE) {
                        continue;
                    }
                    List<MedDraCoder.Suggestion> top = m.suggest(r.reportedTerm(), 1);
                    if (top.isEmpty()) {
                        confident = false;
                        continue;
                    }
                    d.reactions.set(i, suggested(r.reportedTerm(), top.get(0), m));
                    changed = true;
                    confident &= top.get(0).score() >= m.threshold();
                }
            }
        } catch (Exception e) {
            log.warn("coder failed for case {}: {}", c.getId(), e.toString());
            cases.note(c.getId(), "pipeline", "CODING", "coder error: needs manual coding");
            return Status.NEEDS_MANUAL_CODING;
        }
        String actor = "model:" + m.info().name() + "@" + m.info().version();
        if (changed) {
            cases.revise(c.getId(), cases.load(c.getId()).getLockVersion(), actor, "coding suggestions", x -> {
                x.reactions.clear();
                x.reactions.addAll(d.reactions);
            });
        }
        return confident ? Status.CODED : Status.NEEDS_MANUAL_CODING;
    }

    private static VersionDraft.ReactionDraft suggested(String term, MedDraCoder.Suggestion s, MedDraCoder m) {
        return new VersionDraft.ReactionDraft(term, s.pt(), CodeSource.MODEL, m.info().name(), m.info().version(),
                DuplicateCheck.score5(s.score()));
    }
}
