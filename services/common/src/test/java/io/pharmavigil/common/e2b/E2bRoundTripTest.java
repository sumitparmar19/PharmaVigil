// E2B(R3) writer/reader: a case survives a round trip field by field, re-export is XML-identical, hostile or
// foreign input is rejected, and schema validation runs whenever the official schema set is present
// (CI fetches it; without it, nothing is ever reported valid).
package io.pharmavigil.common.e2b;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.diff.Diff;

class E2bRoundTripTest {

    static IcsrCase sample() {
        return new IcsrCase("PV-BATCH-1", "PV-MSG-1", "PHARMAVIGIL", "MOCK-REGULATOR",
                OffsetDateTime.of(2026, 10, 2, 9, 30, 0, 0, ZoneOffset.UTC), "US-PHARMAVIGIL-PV-2026-0000000001",
                "US-PHARMAVIGIL-PV-2026-0000000001", 1, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), 2, "US",
                new BigDecimal("54"), 2,
                List.of(new IcsrCase.Reaction("muscle pain", "myalgia", null, null, LocalDate.of(2026, 9, 28),
                                false, false, false, false, false, false),
                        new IcsrCase.Reaction("dark urine & weakness", "rhabdomyolysis", null, null, null,
                                false, false, true, false, false, false)),
                List.of(new IcsrCase.Drug(1, "Atorvastatin", "83367", "20 mg daily", null),
                        new IcsrCase.Drug(2, "Lisinopril", "29046", null, null)),
                "A 54-year-old woman developed muscle pain <three> weeks after starting atorvastatin.");
    }

    @Test
    void roundTripPreservesEveryMappedField() {
        IcsrCase original = sample();
        String xml = new E2bWriter().write(original);
        IcsrCase back = new E2bReader().read(xml).get(0);
        assertThat(back.safetyReportId()).isEqualTo(original.safetyReportId());
        assertThat(back.batchNumber()).isEqualTo("PV-BATCH-1");
        assertThat(back.senderId()).isEqualTo("PHARMAVIGIL");
        assertThat(back.receiverId()).isEqualTo("MOCK-REGULATOR");
        assertThat(back.createdAt()).isEqualTo(original.createdAt());
        assertThat(back.firstReceived()).isEqualTo(original.firstReceived());
        assertThat(back.reporterQualification()).isEqualTo(2);
        assertThat(back.reporterCountry()).isEqualTo("US");
        assertThat(back.patientAgeYears()).isEqualByComparingTo("54");
        assertThat(back.patientSex()).isEqualTo(2);
        assertThat(back.reactions()).isEqualTo(original.reactions());
        assertThat(back.drugs()).isEqualTo(original.drugs());
        assertThat(back.narrative()).isEqualTo(original.narrative());
        assertThat(xml).contains("&lt;three&gt;").contains("dark urine &amp; weakness");
    }

    @Test
    void reExportIsXmlIdenticalApartFromGeneratedIds() {
        String first = new E2bWriter().write(sample());
        String second = new E2bWriter().write(new E2bReader().read(first).get(0));
        Diff diff = DiffBuilder.compare(first).withTest(second).ignoreWhitespace()
                .withAttributeFilter(a -> !("root".equals(a.getName())
                        && a.getOwnerElement().getParentNode().getLocalName().matches("substanceAdministration|productUseReference")))
                .checkForSimilar().build();
        assertThat(diff.hasDifferences()).as(diff.toString()).isFalse();
    }

    @Test
    void rejectsMalformedForeignAndHostileInput() {
        E2bReader reader = new E2bReader();
        assertThatThrownBy(() -> reader.read("<not-closed>")).isInstanceOf(E2bReader.E2bFormatException.class);
        assertThatThrownBy(() -> reader.read("<ichicsr/>")).hasMessageContaining("MCCI_IN200100UV01");
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<MCCI_IN200100UV01 xmlns=\"urn:hl7-org:v3\">&e;</MCCI_IN200100UV01>";
        assertThatThrownBy(() -> reader.read(xxe)).isInstanceOf(E2bReader.E2bFormatException.class);
        assertThatThrownBy(() -> reader.read("<MCCI_IN200100UV01 xmlns=\"urn:hl7-org:v3\"/>"))
                .hasMessageContaining("no PORR_IN049016UV");
    }

    @Test
    void withoutSchemasNothingIsReportedValid() {
        E2bValidator v = new E2bValidator(Path.of("/nonexistent"));
        assertThat(v.schemaAvailable()).isFalse();
        E2bValidator.Result r = v.validate(new E2bWriter().write(sample()));
        assertThat(r.valid()).isFalse();
        assertThat(r.outcome()).isEqualTo(E2bValidator.Outcome.SCHEMA_UNAVAILABLE);
        assertThat(r.errors().get(0)).contains("fetch-e2b-xsd");
    }

    @Test
    void exportValidatesAgainstOfficialSchemaWhenAvailable() {
        String dir = System.getenv("PV_E2B_XSD_DIR");
        Assumptions.assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "ICH schema set not fetched");
        E2bValidator.Result r = new E2bValidator(Path.of(dir)).validate(new E2bWriter().write(sample()));
        assertThat(r.errors()).isEmpty();
        assertThat(r.valid()).isTrue();
    }
}
