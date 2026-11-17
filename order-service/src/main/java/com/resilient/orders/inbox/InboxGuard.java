package com.resilient.orders.inbox;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * Inbox pattern: record each processed message id in the same transaction as its effect.
 * Kafka delivers at least once, so a redelivered message finds its id here and is skipped.
 * If two deliveries ever raced, the primary key rejects the second one and its retry then skips.
 */
@Component
public class InboxGuard {

    private final ProcessedMessageRepository repository;
    private final Clock clock;

    public InboxGuard(ProcessedMessageRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** True the first time this id is seen, false for every later delivery. */
    public boolean firstTime(UUID messageId) {
        if (repository.existsById(messageId)) {
            return false;
        }
        repository.save(new ProcessedMessage(messageId, clock.instant()));
        return true;
    }
}
