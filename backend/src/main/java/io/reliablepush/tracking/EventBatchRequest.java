package io.reliablepush.tracking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Body of {@code POST /v1/events}: 1 to {@value TrackingService#MAX_BATCH} events. */
public record EventBatchRequest(
        @NotNull @Size(min = 1, max = TrackingService.MAX_BATCH) List<@NotNull @Valid DeviceEvent> events) {}
