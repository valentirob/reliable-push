package io.reliablepush.support;

import io.reliablepush.shared.web.ConflictException;
import io.reliablepush.shared.web.ForbiddenException;
import io.reliablepush.shared.web.NotFoundException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import java.sql.SQLTransientConnectionException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Test-only endpoints under the sender-API prefix, so the body-size limit, the sender filters and the problem
 * mapping can be exercised before the real notifications controller exists.
 */
@RestController
@RequestMapping("/v1/notifications/_test-echo")
class EchoTestController {

    record Probe(@NotBlank String name) {}

    /** Reads (and thereby consumes) the JSON body. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Integer> echo(@RequestBody Map<String, Object> body) {
        return Map.of("fields", body.size());
    }

    @PostMapping(path = "/validated", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, String> validated(@Valid @RequestBody Probe probe) {
        return Map.of("name", probe.name());
    }

    /** Answers 200 only if the container parsed a multipart body, which the API must never do. */
    @PostMapping(path = "/multipart", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Long> multipart(@RequestParam("file") MultipartFile file) {
        return Map.of("size", file.getSize());
    }

    /** Answers 200 only if the container parsed a form body, which the API must never do. */
    @PostMapping(path = "/form", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    Map<String, Integer> form(@RequestParam("a") String a) {
        return Map.of("length", a.length());
    }

    @GetMapping("/throw/{kind}")
    void fail(@PathVariable String kind) {
        switch (kind) {
            case "not-found" -> throw new NotFoundException("no such thing");
            case "conflict" -> throw new ConflictException("wrong state");
            case "forbidden" -> throw new ForbiddenException("not yours");
            case "constraint" -> throw new ConstraintViolationException(Validation.buildDefaultValidatorFactory()
                    .getValidator()
                    .validate(new Probe("")));
            case "other" -> throw new IllegalStateException("secret-internal-detail");
            // what JdbcClient / TransactionTemplate throw when the Hikari pool has no connection within connection-timeout
            case "db-pool" -> throw new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
                    new SQLTransientConnectionException("HikariPool-1 - pool-timeout-marker request timed out after 5000ms"));
            case "db-tx" -> throw new CannotCreateTransactionException("Could not open JDBC Connection for transaction",
                    new SQLTransientConnectionException("HikariPool-1 - pool-timeout-marker request timed out after 5000ms"));
            default -> throw new IllegalArgumentException(kind);
        }
    }
}
