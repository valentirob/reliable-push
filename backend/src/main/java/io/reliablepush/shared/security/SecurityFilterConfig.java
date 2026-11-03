package io.reliablepush.shared.security;

import io.reliablepush.apps.AppRegistry;
import io.reliablepush.shared.web.ProblemWriter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Chain: TraceId (highest) -> AccessLog (+1) -> BodySizeLimit (+10) -> SenderApiKey (+20) -> RateLimit (+30). */
@Configuration(proxyBeanMethods = false)
class SecurityFilterConfig {

    static final int SENDER_API_KEY_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    @Bean
    FilterRegistrationBean<SenderApiKeyFilter> senderApiKeyFilter(AppRegistry apps, ProblemWriter problems) {
        FilterRegistrationBean<SenderApiKeyFilter> registration =
                new FilterRegistrationBean<>(new SenderApiKeyFilter(apps, problems));
        registration.setOrder(SENDER_API_KEY_ORDER);
        return registration;
    }
}
