package com.resilient.inventory.inbox;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "processed_messages")
public class ProcessedMessage {

    @Id
    private UUID messageId;

    private Instant processedAt;

    public ProcessedMessage(UUID messageId, Instant processedAt) {
        this.messageId = messageId;
        this.processedAt = processedAt;
    }
}
