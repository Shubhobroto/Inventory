package com.resilient.payment.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OutboxRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    OutboxRepository repository;

    private OutboxEvent event(String key) {
        return repository.saveAndFlush(new OutboxEvent(UUID.randomUUID(), "topic", key, "Type", "{}", NOW));
    }

    @Test
    void claimsUnpublishedMessagesOldestFirstAndHonoursTheLimit() {
        OutboxEvent a = event("a");
        OutboxEvent b = event("b");
        event("c");

        List<OutboxEvent> claimed = repository.claimUnpublished(2);

        assertThat(claimed).extracting(OutboxEvent::getId).containsExactly(a.getId(), b.getId());
    }

    @Test
    void publishedMessagesAreNotClaimedAgain() {
        OutboxEvent done = event("done");
        done.setPublishedAt(NOW);
        repository.saveAndFlush(done);
        OutboxEvent waiting = event("waiting");

        assertThat(repository.claimUnpublished(10)).extracting(OutboxEvent::getId).containsExactly(waiting.getId());
        assertThat(repository.countByPublishedAtIsNull()).isEqualTo(1);
    }

    @Test
    void cleanupRemovesOnlyOldPublishedMessages() {
        OutboxEvent old = event("old");
        old.setPublishedAt(NOW.minusSeconds(3600));
        repository.saveAndFlush(old);
        OutboxEvent recent = event("recent");
        recent.setPublishedAt(NOW);
        repository.saveAndFlush(recent);
        OutboxEvent unpublished = event("unpublished");

        int removed = repository.deletePublishedBefore(NOW.minusSeconds(60));

        assertThat(removed).isEqualTo(1);
        assertThat(repository.findAll()).extracting(OutboxEvent::getId)
                .containsExactlyInAnyOrder(recent.getId(), unpublished.getId());
    }
}
