package com.resilient.inventory.outbox;

import com.resilient.inventory.inbox.ProcessedMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** Keeps the outbox and inbox tables from growing forever. */
@Slf4j
@Component
public class OutboxCleaner {

    private final OutboxRepository outbox;
    private final ProcessedMessageRepository inbox;
    private final OutboxProperties props;
    private final Clock clock;

    public OutboxCleaner(OutboxRepository outbox, ProcessedMessageRepository inbox, OutboxProperties props, Clock clock) {
        this.outbox = outbox;
        this.inbox = inbox;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${outbox.cleanup-interval-ms:3600000}")
    @Transactional
    public void purge() {
        Instant cutoff = clock.instant().minus(props.retention());
        int sent = outbox.deletePublishedBefore(cutoff);
        int seen = inbox.deleteOlderThan(cutoff);
        if (sent + seen > 0) {
            log.info("Purged {} published outbox rows and {} processed-message ids", sent, seen);
        }
    }
}
