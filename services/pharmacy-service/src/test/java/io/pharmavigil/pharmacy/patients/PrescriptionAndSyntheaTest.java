// Prescription draw-down rules and the Synthea patients.csv parser, without a database.
package io.pharmavigil.pharmacy.patients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.pharmavigil.pharmacy.catalog.Drug;

class PrescriptionAndSyntheaTest {

    @Test
    void prescriptionCompletesWhenFullyFilled() {
        Prescription rx = new Prescription(null, null, new Drug("P", "1", "D", null, null, true, 1), LocalDate.now(), 5);
        rx.fill(3);
        assertThat(rx.getRemainingQuantity()).isEqualTo(2);
        assertThatThrownBy(() -> rx.fill(3)).isInstanceOf(IllegalStateException.class);
        rx.fill(2);
        assertThat(rx.getStatus()).isEqualTo(Prescription.Status.COMPLETED);
        assertThatThrownBy(() -> rx.fill(1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Prescription(null, null, null, LocalDate.now(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesSyntheaColumnsByNameAndCleansNames() throws Exception {
        String csv = """
                Id,BIRTHDATE,DEATHDATE,SSN,FIRST,LAST,GENDER,ADDRESS
                a1,1980-02-03,,999-00-0000,Ana123,"Rivera45",F,"1 Main St, Apt 2"
                bad,not-a-date,,,X,Y,M,
                """;
        List<Patient> parsed = SyntheaImporter.parse(new StringReader(csv));
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).getGivenName()).isEqualTo("Ana");
        assertThat(parsed.get(0).getFamilyName()).isEqualTo("Rivera");
        assertThat(parsed.get(0).getBirthDate()).isEqualTo(LocalDate.of(1980, 2, 3));
        assertThat(parsed.get(1)).isNull(); // invalid row counted, not imported
    }

    @Test
    void rejectsFilesThatAreNotSyntheaPatients() {
        assertThatThrownBy(() -> SyntheaImporter.parse(new StringReader("a,b\n1,2\n")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
