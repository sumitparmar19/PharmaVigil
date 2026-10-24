// Demo mode only: start a replay of real FAERS reports and follow it (the "Run the pipeline" button). Any
// signed-in demo user may start one; limits (one run at a time per account, cooldown, size cap, two runs at
// once) are enforced in DemoReplay. The counters after intake come from case-service (/api/cases/demo/runs).
package io.pharmavigil.intake.demo;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.pharmavigil.common.web.NotFoundException;

@RestController
@RequestMapping("/api/intake/demo")
@ConditionalOnProperty(name = "pv.demo.enabled", havingValue = "true")
@PreAuthorize("hasAnyRole('PHARMACIST','SAFETY_REVIEWER','ADMIN')")
public class DemoController {

    public record StartRun(Integer size) {
    }

    private final DemoReplay replay;

    public DemoController(DemoReplay replay) {
        this.replay = replay;
    }

    @GetMapping
    public Map<String, Object> info() {
        return Map.of("reportsAvailable", replay.available(), "maxPerRun", DemoReplay.MAX_REPORTS);
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DemoReplay.RunView start(@RequestBody(required = false) StartRun body, Authentication auth) {
        int size = body == null || body.size() == null ? DemoReplay.MAX_REPORTS : body.size();
        return replay.start(auth.getName(), size);
    }

    @GetMapping("/runs/{runId}")
    public DemoReplay.RunView run(@PathVariable String runId) {
        DemoReplay.RunView v = replay.get(runId);
        if (v == null) {
            throw new NotFoundException("no demo run " + runId);
        }
        return v;
    }
}
