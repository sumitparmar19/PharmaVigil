// Minimal RFC 4180 CSV reader for the files pandas writes (header row, quoted fields with "" escapes, commas or
// line breaks inside quotes). Empty fields become null.
package io.pharmavigil.signal.store;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class Csv {

    private Csv() {
    }

    /** Calls the consumer once per data row with a column-name → value map. Returns the number of rows. */
    public static long read(BufferedReader in, Consumer<Map<String, String>> row) throws IOException {
        List<String> header = record(in);
        if (header == null) {
            return 0;
        }
        long n = 0;
        List<String> fields;
        while ((fields = record(in)) != null) {
            if (fields.size() == 1 && fields.get(0) == null) {
                continue; // blank line
            }
            if (fields.size() != header.size()) {
                throw new IOException("row " + (n + 1) + " has " + fields.size() + " fields, header has "
                        + header.size());
            }
            Map<String, String> m = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                m.put(header.get(i), fields.get(i));
            }
            row.accept(m);
            n++;
        }
        return n;
    }

    static List<String> record(BufferedReader in) throws IOException {
        int c = in.read();
        if (c == -1) {
            return null;
        }
        List<String> out = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        while (true) {
            if (c == -1) {
                out.add(value(field, wasQuoted));
                return out;
            }
            char ch = (char) c;
            if (quoted) {
                if (ch == '"') {
                    in.mark(1);
                    int next = in.read();
                    if (next == '"') {
                        field.append('"');
                    } else {
                        quoted = false;
                        if (next != -1) {
                            in.reset();
                        }
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
                wasQuoted = true;
            } else if (ch == ',') {
                out.add(value(field, wasQuoted));
                field.setLength(0);
                wasQuoted = false;
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r') {
                    in.mark(1);
                    if (in.read() != '\n') {
                        in.reset();
                    }
                }
                out.add(value(field, wasQuoted));
                return out;
            } else {
                field.append(ch);
            }
            c = in.read();
        }
    }

    private static String value(StringBuilder field, boolean quoted) {
        return field.isEmpty() && !quoted ? null : field.toString();
    }
}
