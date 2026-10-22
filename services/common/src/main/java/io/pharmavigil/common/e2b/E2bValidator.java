// Validates E2B(R3) XML against the official ICH schema set. The schemas are not committed (licence, see
// docs/E2B_R3.md); scripts/fetch-e2b-xsd.sh downloads them at build time into the directory named by
// pv.e2b.xsd-dir / PV_E2B_XSD_DIR. Without the schemas nothing is reported valid: an ICSR that has not
// passed validation can never be queued for submission (engineering rule 7).
package io.pharmavigil.common.e2b;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

public class E2bValidator {

    /** Entry schema of an ICH ICSR batch (relative to the schema-set root). */
    public static final String BATCH_SCHEMA = "multicacheschemas/MCCI_IN200100UV01.xsd";

    public enum Outcome { VALID, INVALID, SCHEMA_UNAVAILABLE }

    public record Result(Outcome outcome, List<String> errors) {
        public boolean valid() {
            return outcome == Outcome.VALID;
        }
    }

    private final Schema schema;
    private final String unavailableReason;

    public E2bValidator(Path schemaRoot) {
        Schema s = null;
        String reason = null;
        Path entry = schemaRoot == null ? null : schemaRoot.resolve(BATCH_SCHEMA);
        if (entry == null || !Files.isRegularFile(entry)) {
            reason = "ICH E2B(R3) schema set not found" + (entry == null ? "" : " at " + entry)
                    + "; run scripts/fetch-e2b-xsd.sh";
        } else {
            try {
                SchemaFactory f = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                f.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
                s = f.newSchema(entry.toFile());
            } catch (SAXException e) {
                reason = "ICH E2B(R3) schema set could not be loaded: " + e.getMessage();
            }
        }
        this.schema = s;
        this.unavailableReason = reason;
    }

    public boolean schemaAvailable() {
        return schema != null;
    }

    public Result validate(String xml) {
        if (schema == null) {
            return new Result(Outcome.SCHEMA_UNAVAILABLE, List.of(unavailableReason));
        }
        List<String> errors = new ArrayList<>();
        try {
            Validator v = schema.newValidator();
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            v.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException e) {
                }

                @Override
                public void error(SAXParseException e) {
                    errors.add(describe(e));
                }

                @Override
                public void fatalError(SAXParseException e) {
                    errors.add(describe(e));
                }
            });
            v.validate(new StreamSource(new StringReader(xml)));
        } catch (SAXException | java.io.IOException e) {
            if (errors.isEmpty()) {
                errors.add(e.getMessage());
            }
        }
        return new Result(errors.isEmpty() ? Outcome.VALID : Outcome.INVALID, List.copyOf(errors));
    }

    private static String describe(SAXParseException e) {
        return "line " + e.getLineNumber() + ", column " + e.getColumnNumber() + ": " + e.getMessage();
    }
}
