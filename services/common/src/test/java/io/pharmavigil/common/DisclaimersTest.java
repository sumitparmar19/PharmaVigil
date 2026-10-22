// Guards the exact disclaimer wording, which the UI and README repeat.
package io.pharmavigil.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DisclaimersTest {

    @Test
    void causationDisclaimerIsExplicit() {
        assertThat(Disclaimers.NO_CAUSATION).contains("do not prove");
        assertThat(Disclaimers.NOT_FOR_CLINICAL_USE).contains("Not for clinical");
        assertThat(Disclaimers.SYNTHETIC_PATIENTS).contains("Synthetic");
    }
}
