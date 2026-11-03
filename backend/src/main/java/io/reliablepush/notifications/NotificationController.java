package io.reliablepush.notifications;

import io.reliablepush.shared.security.SenderPrincipal;
import io.reliablepush.shared.web.InvalidRequestException;
import io.reliablepush.shared.web.NotFoundException;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sender API (spec 4.1, {@code openapi.yaml}). The app comes from the API key ({@link SenderPrincipal}, set by the
 * sender filter), never from the request. Query parameters are parsed here so that every malformed value is a
 * {@code 422} naming the parameter, like a body validation error.
 */
@RestController
@RequestMapping("/v1/notifications")
class NotificationController {

    private static final int DEFAULT_LIMIT = 20;

    private final NotificationService service;

    NotificationController(NotificationService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<NotificationView> register(
            @RequestAttribute(SenderPrincipal.ATTR) SenderPrincipal principal,
            @Valid @RequestBody RegisterNotificationRequest request) {
        NotificationView view = service.register(principal.appId(), request);
        return ResponseEntity.created(URI.create("/v1/notifications/" + view.trackingId())).body(view);
    }

    @GetMapping("/{trackingId}")
    NotificationView get(@RequestAttribute(SenderPrincipal.ATTR) SenderPrincipal principal, @PathVariable String trackingId) {
        return service.get(principal.appId(), parseId(trackingId)).orElseThrow(() -> new NotFoundException("Notification not found."));
    }

    @GetMapping
    NotificationPage list(
            @RequestAttribute(SenderPrincipal.ATTR) SenderPrincipal principal,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        return service.list(
                principal.appId(), userId, parseState(state), parseSince(since), parseLimit(limit), cursor);
    }

    @DeleteMapping("/{trackingId}")
    ResponseEntity<Void> cancel(@RequestAttribute(SenderPrincipal.ATTR) SenderPrincipal principal, @PathVariable String trackingId) {
        service.cancel(principal.appId(), parseId(trackingId));
        return ResponseEntity.noContent().build();
    }

    /** A malformed id cannot exist: 404, like an unknown one. */
    private static UUID parseId(String trackingId) {
        try {
            return UUID.fromString(trackingId);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Notification not found.");
        }
    }

    private static NotificationState parseState(String state) {
        if (state == null) {
            return null;
        }
        try {
            return NotificationState.fromExternal(state);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("state", "is not a notification state");
        }
    }

    private static Instant parseSince(String since) {
        if (since == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(since).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("since", "must be an ISO-8601 date-time");
        }
    }

    private static int parseLimit(String limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        try {
            return Integer.parseInt(limit);
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("limit", "must be an integer between 1 and " + NotificationService.MAX_LIMIT);
        }
    }
}
