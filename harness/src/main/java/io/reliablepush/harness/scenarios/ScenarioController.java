package io.reliablepush.harness.scenarios;

import io.reliablepush.harness.config.HarnessProperties;
import io.reliablepush.harness.receiver.ReceivedWebhook;
import io.reliablepush.harness.receiver.WebhookInbox;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scenario REST used by the console and {@code scripts/e2e.sh}: list, run, prepare/verify of external-control
 * scenarios, the last run's reports, and the receiver's in-flight calls (so the script knows when to kill workers).
 * Concurrent runs answer 409 problem+json. No endpoint returns secrets or device tokens.
 */
@RestController
@RequestMapping("/console/api")
public class ScenarioController {

    /** {@code names} null or empty = every scenario that is not external-control. */
    public record RunRequest(List<String> names) {}

    /** {@code trackingId} null = the one prepare returned. */
    public record VerifyRequest(String trackingId) {}

    private final ScenarioRunner runner;
    private final WebhookInbox inbox;
    private final HarnessProperties properties;

    public ScenarioController(ScenarioRunner runner, WebhookInbox inbox, HarnessProperties properties) {
        this.runner = runner;
        this.inbox = inbox;
        this.properties = properties;
    }

    @GetMapping("/scenarios")
    public List<Map<String, Object>> list() {
        return runner.scenarios().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("description", s.description());
            m.put("externalControl", s.externalControl());
            return m;
        }).toList();
    }

    @PostMapping("/scenarios/run")
    public Map<String, Object> run(@RequestBody(required = false) RunRequest request) {
        List<ScenarioResult> results = request == null || request.names() == null || request.names().isEmpty()
                ? runner.runAll()
                : runner.run(request.names());
        return Map.of("results", results.stream().map(ScenarioReport::toJson).toList());
    }

    @PostMapping("/scenarios/{name}/prepare")
    public Map<String, Object> prepare(@PathVariable String name) {
        ScenarioRunner.Prepared p = runner.prepare(name);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenario", name);
        m.put("trackingId", p.trackingId() == null ? null : p.trackingId().toString());
        m.put("result", p.result() == null ? null : ScenarioReport.toJson(p.result()));
        return m;
    }

    @PostMapping("/scenarios/{name}/verify")
    public Map<String, Object> verify(@PathVariable String name,
                                      @RequestBody(required = false) VerifyRequest request) {
        UUID id = null;
        if (request != null && request.trackingId() != null) {
            try {
                id = UUID.fromString(request.trackingId());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("trackingId is not a UUID");
            }
        }
        return Map.of("result", ScenarioReport.toJson(runner.verify(name, id)));
    }

    @GetMapping(value = "/scenarios/report/summary.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public String summary() {
        return ScenarioReport.summaryJson(runner.lastResults());
    }

    @GetMapping(value = "/scenarios/report/junit.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public String junit() {
        return ScenarioReport.junitXml(runner.lastResults());
    }

    /** {@code {appId, count, trackingIds}}: receiver calls of the app that arrived and are not answered yet. */
    @GetMapping("/inbox/in-flight")
    public ResponseEntity<Object> inFlight(@RequestParam(required = false) String appId) {
        if (appId == null || appId.isBlank()) {
            return problem(HttpStatus.BAD_REQUEST, "Bad request", "query parameter appId is required");
        }
        if (!properties.apps().containsKey(appId)) {
            return problem(HttpStatus.NOT_FOUND, "Unknown app", "unknown app: " + appId);
        }
        List<ReceivedWebhook> pending = inbox.inFlight().stream().filter(w -> w.appId().equals(appId)).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appId", appId);
        m.put("count", pending.size());
        m.put("trackingIds", pending.stream().map(ReceivedWebhook::trackingId).filter(Objects::nonNull)
                .map(UUID::toString).distinct().toList());
        return ResponseEntity.ok(m);
    }

    @ExceptionHandler(ScenarioRunner.RunInProgressException.class)
    ResponseEntity<Object> inProgress(ScenarioRunner.RunInProgressException e) {
        return problem(HttpStatus.CONFLICT, "Scenario run in progress", e.getMessage());
    }

    @ExceptionHandler(ScenarioRunner.UnknownScenarioException.class)
    ResponseEntity<Object> unknown(ScenarioRunner.UnknownScenarioException e) {
        return problem(HttpStatus.NOT_FOUND, "Unknown scenario", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Object> badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage());
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }
}
