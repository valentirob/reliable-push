package io.reliablepush.shared.security;

import io.reliablepush.apps.AppRegistry;
import io.reliablepush.devices.DeviceService;
import io.reliablepush.devices.DeviceTokenService;
import io.reliablepush.shared.web.ProblemWriter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Chain: TraceId (highest) -> AccessLog (+1) -> BodySizeLimit (+10) -> SenderApiKey (+20, sender API) and
 * DeviceAuth (+25, device API; the two cover disjoint paths) -> RateLimit (+30).
 */
@Configuration(proxyBeanMethods = false)
class SecurityFilterConfig {

    static final int SENDER_API_KEY_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;
    static final int DEVICE_AUTH_ORDER = Ordered.HIGHEST_PRECEDENCE + 25;

    @Bean
    FilterRegistrationBean<SenderApiKeyFilter> senderApiKeyFilter(AppRegistry apps, ProblemWriter problems) {
        FilterRegistrationBean<SenderApiKeyFilter> registration =
                new FilterRegistrationBean<>(new SenderApiKeyFilter(apps, problems));
        registration.setOrder(SENDER_API_KEY_ORDER);
        return registration;
    }

    @Bean
    FilterRegistrationBean<DeviceAuthFilter> deviceAuthFilter(
            DeviceTokenService tokens, DeviceService devices, ProblemWriter problems) {
        FilterRegistrationBean<DeviceAuthFilter> registration =
                new FilterRegistrationBean<>(new DeviceAuthFilter(tokens, devices, problems));
        registration.setOrder(DEVICE_AUTH_ORDER);
        return registration;
    }
}
