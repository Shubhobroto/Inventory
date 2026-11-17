package com.resilient.orders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "saga")
public record SagaProperties(
        // An order idle in a non-final state for this long is cancelled and compensated.
        @DefaultValue("60s") Duration timeout,
        // Orders handled per timeout sweep.
        @DefaultValue("50") int timeoutBatchSize
) {
}
