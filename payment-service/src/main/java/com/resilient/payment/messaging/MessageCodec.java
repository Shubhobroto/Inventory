package com.resilient.payment.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Turns messages into JSON and back. A message that cannot be read is never worth retrying. */
@Component
public class MessageCodec {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public Envelope wrap(String orderId, Object payload, Instant now) {
        return new Envelope(UUID.randomUUID(), payload.getClass().getSimpleName(), orderId, now,
                mapper.valueToTree(payload));
    }

    public String serialize(Envelope envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize message " + envelope.messageId(), e);
        }
    }

    public Envelope parse(String json) {
        try {
            return mapper.readValue(json, Envelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed message", e);
        }
    }

    public <T> T payload(Envelope envelope, Class<T> type) {
        try {
            return mapper.treeToValue(envelope.payload(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed " + envelope.type() + " payload", e);
        }
    }
}
