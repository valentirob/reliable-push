package io.reliablepush.shared.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single time source for the application. Production code reads time only from this {@link Clock}. Tests that need
 * a controllable clock register their own {@code Clock} bean marked {@code @Primary}.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
