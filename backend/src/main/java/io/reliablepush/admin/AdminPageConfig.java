package io.reliablepush.admin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Maps the dashboard files ({@code classpath:/admin-ui/}, deliberately not under Boot's default {@code /static/}) to
 * {@code /admin/**} only when admin keys are configured, so a disabled instance serves nothing there and answers
 * exactly like an unknown path.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("reliable-push.admin.keys[0].id")
class AdminPageConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/admin/**").addResourceLocations("classpath:/admin-ui/");
    }
}
