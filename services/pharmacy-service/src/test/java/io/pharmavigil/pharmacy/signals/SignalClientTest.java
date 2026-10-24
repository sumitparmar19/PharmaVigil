// The signal client returns evidence when signal-service answers and "unavailable" (never an exception)
// when it is down or slow, so dispensing is never blocked.
package io.pharmavigil.pharmacy.signals;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

class SignalClientTest {

    @Test
    void readsWarningsFromSignalService() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/signals/warnings", ex -> {
            byte[] body = """
                    [{"rxcui":"83367","pt":"rhabdomyolysis","reports":120,"ebgm":4.2,"eb05":3.1,
                      "safetyformer":1.7,"labelText":null,"disclaimer":"Spontaneous reports do not prove causation."}]
                    """.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            SignalClient client = new SignalClient(RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2));
            SignalLookup lookup = client.warningsFor("83367");
            assertThat(lookup.available()).isTrue();
            assertThat(lookup.warnings()).singleElement().satisfies(w -> {
                assertThat(w.pt()).isEqualTo("rhabdomyolysis");
                assertThat(w.reports()).isEqualTo(120);
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unreachableServiceGivesUnavailableNotAnError() {
        SignalClient client = new SignalClient(RestClient.builder(), "http://127.0.0.1:9", Duration.ofMillis(300));
        SignalLookup lookup = client.warningsFor("83367");
        assertThat(lookup.available()).isFalse();
        assertThat(lookup.warnings()).isEmpty();
        assertThat(lookup.note()).contains("not blocked");
    }
}
