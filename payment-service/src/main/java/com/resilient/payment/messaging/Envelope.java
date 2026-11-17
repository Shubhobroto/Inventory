package com.resilient.payment.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Every Kafka message is wrapped in this envelope.
 * messageId is what consumers use to detect duplicates; orderId is the Kafka key, so all messages of one
 * order land in the same partition and are processed in order.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Envelope(UUID messageId, String type, String orderId, Instant occurredAt, JsonNode payload) {
}
