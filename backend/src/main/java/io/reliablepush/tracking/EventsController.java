package io.reliablepush.tracking;

import io.reliablepush.shared.security.DevicePrincipal;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Device events API (spec 4.2, {@code openapi.yaml}). The device comes from its token ({@link DevicePrincipal}, set by
 * {@code DeviceAuthFilter}); the outcome of every event is reported per item, the request itself succeeds.
 */
@RestController
@RequestMapping("/v1/events")
class EventsController {

    private final TrackingService service;

    EventsController(TrackingService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    EventBatchResult report(
            @RequestAttribute(DevicePrincipal.ATTR) DevicePrincipal principal,
            @Valid @RequestBody EventBatchRequest request) {
        return service.process(principal, request.events());
    }
}
