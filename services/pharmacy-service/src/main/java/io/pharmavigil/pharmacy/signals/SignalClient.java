// HTTP client for signal-service with short timeouts. Any failure yields SignalLookup.unavailable.
package io.pharmavigil.pharmacy.signals;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SignalClient {

    private static final Logger log = LoggerFactory.getLogger(SignalClient.class);
    private final RestClient http;

    public SignalClient(RestClient.Builder builder, @Value("${pv.signal-service.url:http://localhost:8084}") String url,
            @Value("${pv.signal-service.timeout:PT2S}") Duration timeout) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(timeout);
        rf.setReadTimeout(timeout);
        this.http = builder.baseUrl(url).requestFactory(rf).build();
    }

    public SignalLookup warningsFor(String rxcui) {
        try {
            List<SafetyWarning> warnings = http.get().uri("/api/signals/warnings?rxcui={rxcui}", rxcui)
                    .retrieve().body(new ParameterizedTypeReference<List<SafetyWarning>>() { });
            return new SignalLookup(true, warnings == null ? List.of() : warnings, null);
        } catch (RuntimeException e) {
            log.warn("signal-service unavailable for rxcui {}: {}", rxcui, e.toString());
            return SignalLookup.unavailable("Safety signals are temporarily unavailable; dispense was not blocked.");
        }
    }
}
