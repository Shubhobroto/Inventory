package com.resilient.orders.outbox;

import com.resilient.orders.messaging.Envelope;
import com.resilient.orders.messaging.MessageCodec;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Transactional outbox, write side. The message is stored in the same database transaction as the business
 * change, so either both happen or neither does. {@link OutboxPublisher} sends it to Kafka afterwards.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository repository;
    private final MessageCodec codec;
    private final Clock clock;

    public OutboxWriter(OutboxRepository repository, MessageCodec codec, Clock clock) {
        this.repository = repository;
        this.codec = codec;
        this.clock = clock;
    }

    // MANDATORY: refuse to write an outbox row outside a business transaction, which would defeat the pattern.
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String topic, String orderId, Object payload) {
        Envelope envelope = codec.wrap(orderId, payload, clock.instant());
        repository.save(new OutboxEvent(envelope.messageId(), topic, orderId, envelope.type(),
                codec.serialize(envelope), clock.instant()));
    }
}
