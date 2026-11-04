package io.reliablepush.harness;

import io.reliablepush.harness.config.HarnessProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Local test/demo harness. Never deployed to production. */
@SpringBootApplication
@EnableConfigurationProperties(HarnessProperties.class)
public class HarnessApplication {

    public static void main(String[] args) {
        SpringApplication.run(HarnessApplication.class, args);
    }
}
