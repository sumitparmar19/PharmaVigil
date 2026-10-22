// CSV parsing of pandas output (quotes, embedded commas and line breaks, CRLF, empty cells) and the API's
// answers when no export has been loaded: empty lists with the disclaimer, never invented numbers.
package io.pharmavigil.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.pharmavigil.signal.api.SignalController;
import io.pharmavigil.signal.store.Csv;
import io.pharmavigil.signal.store.SignalLoader;
import io.pharmavigil.signal.store.SignalStore;
import tools.jackson.databind.json.JsonMapper;

class CsvAndEmptyStateTest {

    @Test
    void readsQuotedFieldsAndEmptyCells() throws IOException {
        String csv = "rxcui,pt,note\r\n1,\"rash, generalised\",\r\n2,\"line\nbreak\",\"say \"\"hi\"\"\"\n\n";
        List<Map<String, String>> rows = new ArrayList<>();
        long n = Csv.read(new BufferedReader(new StringReader(csv)), rows::add);
        assertThat(n).isEqualTo(2);
        assertThat(rows.get(0)).containsEntry("pt", "rash, generalised").containsEntry("note", null);
        assertThat(rows.get(1)).containsEntry("pt", "line\nbreak").containsEntry("note", "say \"hi\"");
        assertThat(Csv.read(new BufferedReader(new StringReader("")), rows::add)).isZero();
        assertThatThrownBy(() -> Csv.read(new BufferedReader(new StringReader("a,b\n1\n")), r -> { }))
                .hasMessageContaining("fields");
    }

    @Test
    void withoutAnExportEverythingIsEmptyButLabelled() {
        SignalStore store = mock(SignalStore.class);
        when(store.current()).thenReturn(Optional.empty());
        SignalController api = new SignalController(store, mock(SignalLoader.class), JsonMapper.builder().build(),
                2.0, 10, "");
        assertThat(api.warnings("83367")).isEmpty();
        assertThat(api.top("ebgm", 10).signals()).isEmpty();
        assertThat(api.top("ebgm", 10).disclaimer()).contains("do not prove");
        assertThat(api.trend("83367", "rash").points()).isEmpty();
        assertThat(api.meta().loaded()).isFalse();
        assertThatThrownBy(api::reload).hasMessageContaining("PV_SIGNALS_DIR");
    }
}
