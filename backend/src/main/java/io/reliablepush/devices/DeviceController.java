package io.reliablepush.devices;

import io.reliablepush.shared.security.DevicePrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Device API (spec 4.2, {@code openapi.yaml}). Registration is open (it proves identity with the app's credential);
 * refresh and revoke are authenticated by {@code DeviceAuthFilter}, which provides the {@link DevicePrincipal}.
 */
@RestController
@RequestMapping("/v1/devices")
class DeviceController {

    private final DeviceService service;

    DeviceController(DeviceService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<DeviceTokenResponse> register(@Valid @RequestBody RegisterDeviceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.register(request));
    }

    @PostMapping("/token:refresh")
    DeviceTokenResponse refresh(@RequestAttribute(DevicePrincipal.ATTR) DevicePrincipal principal) {
        return service.refresh(principal);
    }

    @DeleteMapping("/me")
    ResponseEntity<Void> revoke(@RequestAttribute(DevicePrincipal.ATTR) DevicePrincipal principal) {
        service.revoke(principal);
        return ResponseEntity.noContent().build();
    }
}
