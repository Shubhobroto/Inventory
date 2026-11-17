package com.resilient.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
public class RuntimeConfig {

    // A bean so tests can replace the clock and make time-based logic deterministic.
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
