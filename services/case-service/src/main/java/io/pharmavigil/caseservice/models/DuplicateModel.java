// Duplicate check (LightGBM → ONNX): record-linkage features between a new report and a candidate case, in
// the order of PAIR_FEATURES in ml/pharmavigil_ml/triage/duplicates.py. The model was trained on a proxy
// (consecutive versions of one FAERS case), so its score is a hint for the reviewer, never a merge.
package io.pharmavigil.caseservice.models;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

public final class DuplicateModel {

    /** The comparable facts of one report or case version. */
    public record Profile(Set<String> drugs, Set<String> pts, String sex, String ageBand, String country,
            String occupation, LocalDate eventDate, LocalDate receivedDate) {
    }

    private final OrtSession session;
    private final ModelInfo info;
    private final double threshold;

    DuplicateModel(OrtSession session, ModelInfo info, double threshold) {
        this.session = session;
        this.info = info;
        this.threshold = threshold;
    }

    public ModelInfo info() {
        return info;
    }

    public double threshold() {
        return threshold;
    }

    public static float[] features(Profile a, Profile b) {
        return new float[] {
            jaccard(a.drugs(), b.drugs()), jaccard(a.pts(), b.pts()), intersection(a.drugs(), b.drugs()),
            intersection(a.pts(), b.pts()), same(a.sex(), b.sex()), same(a.ageBand(), b.ageBand()),
            same(a.country(), b.country()), same(a.occupation(), b.occupation()),
            days(a.eventDate(), b.eventDate()), days(a.receivedDate(), b.receivedDate()),
        };
    }

    public double score(float[] features) throws OrtException {
        return Onnx.positiveProbability(session, features);
    }

    static float jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0f;
        }
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return intersection(a, b) / union.size();
    }

    static float intersection(Set<String> a, Set<String> b) {
        Set<String> i = new HashSet<>(a);
        i.retainAll(b);
        return i.size();
    }

    /** 1 same, 0 different, -1 when either side is unknown. */
    static float same(String a, String b) {
        if (a == null || b == null || a.equals("UNK") || a.equals("unknown") || b.equals("UNK")
                || b.equals("unknown")) {
            return -1f;
        }
        return a.equals(b) ? 1f : 0f;
    }

    static float days(LocalDate a, LocalDate b) {
        return a == null || b == null ? -1f : Math.abs(ChronoUnit.DAYS.between(a, b));
    }
}
