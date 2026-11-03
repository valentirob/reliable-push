package io.reliablepush.support;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/** Test-only servlet filter that fails unexpectedly on {@code /_test-filter-boom}, to exercise the /error path. */
@Configuration(proxyBeanMethods = false)
class ThrowingFilterConfig {

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> throwingTestFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !"/_test-filter-boom".equals(request.getRequestURI());
            }

            @Override
            protected void doFilterInternal(
                    HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, jakarta.servlet.FilterChain chain) {
                throw new IllegalStateException("secret-filter-detail");
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 40);
        return registration;
    }
}
