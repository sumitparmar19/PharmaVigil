// Seriousness triage (LightGBM → ONNX). Builds exactly the feature vector of the Python FeatureSpec
// (ml/pharmavigil_ml/triage/features.py): age band, sex, reporter occupation, country, drug and PT
// indicators and counts. Outcome fields are never inputs, and outcome-revealing PTs (death, hospitalisation,
// ...) are dropped with the same pattern the model was trained with (Amendment 1, item 5).
package io.pharmavigil.caseservice.models;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

public final class SeriousnessModel {

    public record Drug(String rxcui, String role) {
    }

    /** What the intake desk knows before anyone codes an outcome. */
    public record Input(BigDecimal ageYears, String sex, String reporterType, String country, List<Drug> drugs,
            List<String> pts) {
    }

    public record AgeBand(double lo, double hi, String label) {
    }

    static final Map<String, String> OCCUPATION = Map.of("PHYSICIAN", "MD", "PHARMACIST", "PH", "OTHER_HCP", "OT",
            "LAWYER", "LW", "CONSUMER", "CN");
    private static final Set<String> SEXES = Set.of("M", "F", "UNK");

    private final OrtSession session;
    private final ModelInfo info;
    private final Map<String, Integer> pos = new HashMap<>();
    private final int width;
    private final List<AgeBand> ageBands;
    private final Pattern outcomeRevealing;

    SeriousnessModel(OrtSession session, ModelInfo info, List<String> columns, List<AgeBand> ageBands,
            String outcomeRevealingPattern) {
        this.session = session;
        this.info = info;
        for (int i = 0; i < columns.size(); i++) {
            pos.put(columns.get(i), i);
        }
        this.width = columns.size();
        this.ageBands = ageBands;
        // Python's re.IGNORECASE; the pattern uses only ASCII letters, \b and alternation.
        this.outcomeRevealing = Pattern.compile(outcomeRevealingPattern,
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** FAERS occupation code for a PharmaVigil reporter type (null when unknown). */
    public static String occupation(String reporterType) {
        return reporterType == null ? null : OCCUPATION.get(reporterType);
    }

    public ModelInfo info() {
        return info;
    }

    public boolean isOutcomeRevealing(String pt) {
        return outcomeRevealing.matcher(pt).find();
    }

    String ageBand(BigDecimal years) {
        if (years == null) {
            return "unknown";
        }
        double y = years.doubleValue();
        for (AgeBand b : ageBands) {
            if (y >= b.lo() && y < b.hi()) {
                return b.label();
            }
        }
        return "unknown";
    }

    public float[] vectorize(Input in) {
        float[] x = new float[width];
        x[pos.get("age_band=" + ageBand(in.ageYears()))] = 1;
        x[pos.get("sex=" + (in.sex() != null && SEXES.contains(in.sex()) ? in.sex() : "UNK"))] = 1;
        set(x, "occp=" + occupation(in.reporterType()), "occp=other");
        set(x, "country=" + in.country(), "country=other");
        for (Drug d : in.drugs()) {
            x[pos.get("n_drugs")] += 1;
            if ("PS".equals(d.role())) {
                x[pos.get("n_primary_suspect")] += 1;
            }
            Integer i = pos.get("drug=" + d.rxcui());
            if (i != null) {
                x[i] = 1;
            }
        }
        for (String pt : in.pts()) {
            if (isOutcomeRevealing(pt)) {
                continue;
            }
            x[pos.get("n_pts")] += 1;
            Integer i = pos.get("pt=" + pt);
            if (i != null) {
                x[i] = 1;
            }
        }
        return x;
    }

    private void set(float[] x, String column, String fallback) {
        x[pos.getOrDefault(column, pos.get(fallback))] = 1;
    }

    /** Probability that the report is serious. */
    public double predict(Input in) throws OrtException {
        return Onnx.positiveProbability(session, vectorize(in));
    }
}
