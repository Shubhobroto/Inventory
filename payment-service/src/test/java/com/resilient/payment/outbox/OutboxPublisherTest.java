package com.resilient.payment.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private OutboxPublisher publisher;

    private final OutboxEvent first = new OutboxEvent(UUID.randomUUID(), "t", "k1", "A", "p1", NOW);
    private final OutboxEvent second = new OutboxEvent(UUID.randomUUID(), "t", "k2", "B", "p2", NOW);

    @BeforeEach
    void setUp() {
        when(repository.claimUnpublished(100)).thenReturn(List.of(first, second));
        publisher = new OutboxPublisher(repository, kafka,
                new OutboxProperties(100, Duration.ofSeconds(1), Duration.ofDays(7)),
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());
    }

    private static CompletableFuture<SendResult<String, String>> acknowledged() {
        return CompletableFuture.completedFuture(mock(SendResult.class));
    }

    private static CompletableFuture<SendResult<String, String>> failed() {
        return CompletableFuture.failedFuture(new IllegalStateException("broker unavailable"));
    }

    @Test
    void marksAMessagePublishedOnlyAfterTheBrokerAcknowledgedIt() {
        when(kafka.send("t", "k1", "p1")).thenReturn(acknowledged());
        when(kafka.send("t", "k2", "p2")).thenReturn(acknowledged());

        publisher.publishPending();

        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void aFailedSendLeavesTheMessageForTheNextPollAndStopsTheBatch() {
        when(kafka.send("t", "k1", "p1")).thenReturn(failed());

        publisher.publishPending();

        assertThat(first.getPublishedAt()).isNull();
        // the second message must not overtake the first one
        verify(kafka, never()).send("t", "k2", "p2");
        assertThat(second.getPublishedAt()).isNull();
    }

    @Test
    void keepsTheProgressMadeBeforeAFailure() {
        when(kafka.send("t", "k1", "p1")).thenReturn(acknowledged());
        when(kafka.send("t", "k2", "p2")).thenReturn(failed());

        publisher.publishPending();

        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getPublishedAt()).isNull();
    }

    @Test
    void aMessageNotConfirmedInTimeIsNotMarkedPublished() {
        when(kafka.send("t", "k1", "p1")).thenReturn(new CompletableFuture<>());

        publisher.publishPending();

        assertThat(first.getPublishedAt()).isNull();
    }
}
