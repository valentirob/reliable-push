package io.reliablepush.admin;

import io.reliablepush.shared.ratelimit.RateLimiter;
import io.reliablepush.shared.web.ProblemWriter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Admin filters sit next to the other authentication filters; their paths ({@code /v1/admin}, {@code /admin}) are disjoint. */
@Configuration(proxyBeanMethods = false)
class AdminFilterConfig {

    static final int ADMIN_KEY_ORDER = Ordered.HIGHEST_PRECEDENCE + 22;
    static final int ADMIN_PAGE_ORDER = Ordered.HIGHEST_PRECEDENCE + 23;

    @Bean
    FilterRegistrationBean<AdminKeyFilter> adminKeyFilter(AdminKeys keys, RateLimiter limiter, ProblemWriter problems) {
        FilterRegistrationBean<AdminKeyFilter> registration =
                new FilterRegistrationBean<>(new AdminKeyFilter(keys, limiter, problems));
        registration.setOrder(ADMIN_KEY_ORDER);
        return registration;
    }

    @Bean
    FilterRegistrationBean<AdminPageFilter> adminPageFilter(AdminKeys keys, ProblemWriter problems) {
        FilterRegistrationBean<AdminPageFilter> registration =
                new FilterRegistrationBean<>(new AdminPageFilter(keys, problems));
        registration.setOrder(ADMIN_PAGE_ORDER);
        return registration;
    }
}
