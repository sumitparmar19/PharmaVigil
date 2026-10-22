// Loads PV_SIGNALS_DIR at start-up when it is set. A failed load is logged and the service keeps serving the
// snapshot it already has (or none), so a bad export never takes the pharmacy warnings down.
package io.pharmavigil.signal.store;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StartupLoad implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupLoad.class);

    private final SignalLoader loader;
    private final String dir;

    public StartupLoad(SignalLoader loader, @Value("${pv.signals.dir:}") String dir) {
        this.loader = loader;
        this.dir = dir;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (dir == null || dir.isBlank()) {
            log.info("PV_SIGNALS_DIR not set: no signal data is served");
            return;
        }
        try {
            loader.load(Path.of(dir));
        } catch (RuntimeException e) {
            log.warn("signal export in {} not loaded: {}", dir, e.getMessage());
        }
    }
}
