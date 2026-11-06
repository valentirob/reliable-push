package io.reliablepush.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only ops dashboard API. {@link AdminKeyFilter} has authenticated every request before it gets here. */
@RestController
@ConditionalOnProperty("reliable-push.admin.keys[0].id")
@RequestMapping("/v1/admin")
class AdminController {

    record Me(String keyId, List<String> apps, List<String> permissions) {}

    @GetMapping("/me")
    Me me(HttpServletRequest request) {
        AdminPrincipal principal = (AdminPrincipal) request.getAttribute(AdminPrincipal.ATTR);
        return new Me(
                principal.keyId(),
                principal.apps().stream().sorted().toList(),
                principal.payload() ? List.of("payload") : List.of());
    }
}
