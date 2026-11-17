package com.resilient.payment.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    // Identity order is publish order, which keeps messages of one order in sequence.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID messageId;
    private String topic;
    private String messageKey;
    private String eventType;

    @Column(columnDefinition = "text")
    private String payload;

    private Instant createdAt;

    @Setter
    private Instant publishedAt;

    public OutboxEvent(UUID messageId, String topic, String messageKey, String eventType, String payload, Instant createdAt) {
        this.messageId = messageId;
        this.topic = topic;
        this.messageKey = messageKey;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = createdAt;
    }
}
