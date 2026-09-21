package in.vedchangani.billingsoftware.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.time.Clock;

/**
 * {@code @EnableAsync} lets the password-reset mail be sent off the request thread (after the
 * database commit); the Clock bean is the single time source for reset expiry/cooldowns and the
 * in-memory limiter.
 */
@Configuration
@EnableAsync
public class AsyncAndClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
