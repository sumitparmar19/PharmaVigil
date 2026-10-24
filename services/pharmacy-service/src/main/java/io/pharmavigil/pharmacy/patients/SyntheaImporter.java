// Imports synthetic patients from a Synthea `patients.csv` export. Only the fields the pharmacy needs are
// kept (Synthea id, names, birth date, sex); Synthea's synthetic SSN, address and other columns are ignored.
package io.pharmavigil.pharmacy.patients;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SyntheaImporter {

    public record ImportResult(int read, int created, int skippedExisting, int skippedInvalid) {
    }

    private final PatientRepository patients;

    public SyntheaImporter(PatientRepository patients) {
        this.patients = patients;
    }

    /** Parse Synthea CSV rows (columns are found by header name; Synthea versions differ in column order). */
    public static List<Patient> parse(Reader reader) throws IOException {
        BufferedReader in = new BufferedReader(reader);
        String header = in.readLine();
        if (header == null) {
            return List.of();
        }
        Map<String, Integer> col = new HashMap<>();
        String[] names = splitCsv(header);
        for (int i = 0; i < names.length; i++) {
            col.put(names[i].trim().toUpperCase(), i);
        }
        for (String required : List.of("ID", "BIRTHDATE", "FIRST", "LAST", "GENDER")) {
            if (!col.containsKey(required)) {
                throw new IllegalArgumentException("not a Synthea patients.csv: missing column " + required);
            }
        }
        List<Patient> out = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = splitCsv(line);
            try {
                out.add(new Patient(f[col.get("ID")], stripDigits(f[col.get("FIRST")]), stripDigits(f[col.get("LAST")]),
                        LocalDate.parse(f[col.get("BIRTHDATE")]), f[col.get("GENDER")].trim().toUpperCase()));
            } catch (RuntimeException e) {
                out.add(null); // counted as invalid by the caller
            }
        }
        return out;
    }

    @Transactional
    public ImportResult importCsv(Reader reader) throws IOException {
        List<Patient> parsed = parse(reader);
        int created = 0;
        int existing = 0;
        int invalid = 0;
        for (Patient p : parsed) {
            if (p == null) {
                invalid++;
            } else if (patients.findBySyntheticId(p.getSyntheticId()).isPresent()) {
                existing++;
            } else {
                patients.save(p);
                created++;
            }
        }
        return new ImportResult(parsed.size(), created, existing, invalid);
    }

    // Synthea appends digits to names ("Ana123") to keep them unique; the counter shows the plain name.
    private static String stripDigits(String s) {
        String cleaned = s.replaceAll("\\d+$", "").trim();
        return cleaned.isEmpty() ? s.trim() : cleaned;
    }

    // Synthea CSV has no embedded commas in the columns used here, but quoted fields elsewhere are handled.
    static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(String[]::new);
    }
}
