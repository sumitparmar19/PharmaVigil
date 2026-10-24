// Reads and writes the signal snapshots. A load is one transaction: snapshot row, every drug–event row and the
// trend rows commit together, so readers see either the previous snapshot or the complete new one.
package io.pharmavigil.signal.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class SignalStore {

    public record Snapshot(long id, String scope, String sourceSha256, int pairs, boolean withSafetyformer,
            String evaluationJson, Instant loadedAt) {
    }

    public record Signal(String rxcui, String ingredient, String pt, long reports, Double prr, Double prrLo,
            Double prrHi, Double ror, Double rorLo, Double rorHi, Double ic, Double ic025, Double ic975, Double ebgm,
            Double eb05, Double eb95, Double safetyformer, String reference) {
    }

    public record TrendPoint(int year, long reports) {
    }

    static final String[] SCORES = {"prr", "prr_lo", "prr_hi", "ror", "ror_lo", "ror_hi", "ic", "ic025", "ic975",
        "ebgm", "eb05", "eb95", "safetyformer"};
    private static final int BATCH = 1000;

    private final JdbcTemplate jdbc;

    public SignalStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Snapshot> current() {
        List<Snapshot> s = jdbc.query("SELECT id, scope, source_sha256, pairs, with_sf, evaluation, loaded_at "
                + "FROM signal_snapshot ORDER BY id DESC", ps -> ps.setMaxRows(1), (rs, i) -> new Snapshot(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getBoolean(5),
                        rs.getString(6), rs.getTimestamp(7).toInstant()));
        return s.stream().findFirst();
    }

    public boolean hasSource(String sha256) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM signal_snapshot WHERE source_sha256 = ?", Integer.class,
                sha256);
        return n != null && n > 0;
    }

    long insertSnapshot(String scope, String sha256, boolean withSf, String evaluation, Instant at) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO signal_snapshot (scope, source_sha256, pairs, "
                    + "with_sf, evaluation, loaded_at) VALUES (?, ?, 0, ?, ?, ?)", new String[] {"id"});
            ps.setString(1, scope);
            ps.setString(2, sha256);
            ps.setBoolean(3, withSf);
            ps.setString(4, evaluation);
            ps.setTimestamp(5, Timestamp.from(at));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    void setPairs(long snapshotId, int pairs) {
        jdbc.update("UPDATE signal_snapshot SET pairs = ? WHERE id = ?", pairs, snapshotId);
    }

    void insertSignals(long snapshotId, List<Map<String, String>> rows) {
        String sql = "INSERT INTO drug_event_signal (snapshot_id, rxcui, ingredient, pt, a, n_drug, n_event, n_total, "
                + String.join(", ", SCORES) + ", reference) VALUES (?, ?, ?, ?, ?, ?, ?, ?"
                + ", ?".repeat(SCORES.length) + ", ?)";
        for (int from = 0; from < rows.size(); from += BATCH) {
            List<Map<String, String>> chunk = rows.subList(from, Math.min(rows.size(), from + BATCH));
            jdbc.batchUpdate(sql, chunk, chunk.size(), (ps, r) -> {
                ps.setLong(1, snapshotId);
                ps.setString(2, r.get("rxcui"));
                ps.setString(3, r.get("ingredient"));
                ps.setString(4, r.get("pt"));
                ps.setLong(5, (long) Double.parseDouble(r.get("a")));
                setLong(ps, 6, r.get("n_drug"));
                setLong(ps, 7, r.get("n_event"));
                setLong(ps, 8, r.get("n_total"));
                for (int i = 0; i < SCORES.length; i++) {
                    setDouble(ps, 9 + i, r.get(SCORES[i]));
                }
                String ref = r.get("reference"); // absent in exports made before Phase 2
                ps.setString(9 + SCORES.length, ref == null || ref.isBlank() ? null : ref);
            });
        }
    }

    void insertTrend(long snapshotId, List<Map<String, String>> rows) {
        for (int from = 0; from < rows.size(); from += BATCH) {
            List<Map<String, String>> chunk = rows.subList(from, Math.min(rows.size(), from + BATCH));
            jdbc.batchUpdate("INSERT INTO signal_trend (snapshot_id, rxcui, pt, year, reports) VALUES (?, ?, ?, ?, ?)",
                    chunk, chunk.size(), (ps, r) -> {
                        ps.setLong(1, snapshotId);
                        ps.setString(2, r.get("rxcui"));
                        ps.setString(3, r.get("pt"));
                        ps.setInt(4, (int) Double.parseDouble(r.get("year")));
                        ps.setLong(5, (long) Double.parseDouble(r.get("reports")));
                    });
        }
    }

    private static void setLong(PreparedStatement ps, int i, String v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.BIGINT);
        } else {
            ps.setLong(i, (long) Double.parseDouble(v));
        }
    }

    private static void setDouble(PreparedStatement ps, int i, String v) throws SQLException {
        double d = v == null ? Double.NaN : Double.parseDouble(v);
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            ps.setNull(i, Types.DOUBLE);
        } else {
            ps.setDouble(i, d);
        }
    }

    private static Double dbl(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    static final RowMapper<Signal> SIGNAL = (rs, i) -> new Signal(rs.getString("rxcui"), rs.getString("ingredient"),
            rs.getString("pt"), rs.getLong("a"), dbl(rs, "prr"), dbl(rs, "prr_lo"), dbl(rs, "prr_hi"), dbl(rs, "ror"),
            dbl(rs, "ror_lo"), dbl(rs, "ror_hi"), dbl(rs, "ic"), dbl(rs, "ic025"), dbl(rs, "ic975"), dbl(rs, "ebgm"),
            dbl(rs, "eb05"), dbl(rs, "eb95"), dbl(rs, "safetyformer"), rs.getString("reference"));

    /** Pairs of one drug at or above the EB05 threshold, strongest first. */
    public List<Signal> warnings(long snapshotId, String rxcui, double minEb05, int limit) {
        return jdbc.query("SELECT * FROM drug_event_signal WHERE snapshot_id = ? AND rxcui = ? AND eb05 >= ? "
                + "ORDER BY eb05 DESC, a DESC, pt", ps -> {
                    ps.setLong(1, snapshotId);
                    ps.setString(2, rxcui);
                    ps.setDouble(3, minEb05);
                    ps.setMaxRows(limit);
                }, SIGNAL);
    }

    /** Top pairs by one method's ranking score (the lower interval bound where one exists). */
    public List<Signal> top(long snapshotId, String orderColumn, int limit) {
        if (!List.of("eb05", "prr_lo", "ror_lo", "ic025", "safetyformer").contains(orderColumn)) {
            throw new IllegalArgumentException("unknown ranking column " + orderColumn);
        }
        return jdbc.query("SELECT * FROM drug_event_signal WHERE snapshot_id = ? AND " + orderColumn
                + " IS NOT NULL ORDER BY " + orderColumn + " DESC, a DESC, rxcui, pt", ps -> {
                    ps.setLong(1, snapshotId);
                    ps.setMaxRows(limit);
                }, SIGNAL);
    }

    /** Pairs whose ingredient starts with the search text (case-insensitive), strongest EB05 first. */
    public List<Signal> search(long snapshotId, String ingredientPrefix, int limit) {
        // The search text is matched literally: LIKE wildcards in it are escaped, not interpreted.
        String p = ingredientPrefix.strip().toUpperCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return jdbc.query("SELECT * FROM drug_event_signal WHERE snapshot_id = ? AND UPPER(ingredient) LIKE ? "
                + "ESCAPE '!' ORDER BY eb05 DESC, a DESC, pt", ps -> {
                    ps.setLong(1, snapshotId);
                    ps.setString(2, p + "%");
                    ps.setMaxRows(limit);
                }, SIGNAL);
    }

    public List<TrendPoint> trend(long snapshotId, String rxcui, String pt) {
        return new ArrayList<>(jdbc.query("SELECT year, reports FROM signal_trend WHERE snapshot_id = ? AND rxcui = ? "
                + "AND pt = ? ORDER BY year", (rs, i) -> new TrendPoint(rs.getInt(1), rs.getLong(2)), snapshotId,
                rxcui, pt));
    }
}
