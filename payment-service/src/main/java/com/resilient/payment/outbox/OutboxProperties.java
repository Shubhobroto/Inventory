package com.resilient.payment.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "outbox")
public record OutboxProperties(
        // Rows read per poll. Each row waits for a broker acknowledgement, so this bounds one poll's duration.
        @DefaultValue("100") int batchSize,
        // How long to wait for the broker to confirm one message before leaving it for the next poll.
        @DefaultValue("5s") Duration sendTimeout,
        // Published rows and processed-message ids older than this are deleted.
        @DefaultValue("7d") Duration retention
) {
}
