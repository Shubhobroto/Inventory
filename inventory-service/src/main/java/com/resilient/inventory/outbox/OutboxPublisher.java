package com.resilient.inventory.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Transactional outbox, publish side. Reads unpublished rows in order, sends each to Kafka and waits for the
 * broker's acknowledgement before marking it published.
 *
 * Delivery is at least once: if the app dies after Kafka accepted a message but before the row is marked, it is
 * sent again, so consumers must be idempotent (they are: inbox + state guards).
 * On the first failure the batch stops, so a later message never overtakes an earlier one for the same order.
 */
@Slf4j
@Component
public class OutboxPublisher {

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties props;
    private final Clock clock;
    private final Counter published;
    private final Counter failures;

    public OutboxPublisher(OutboxRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                           OutboxProperties props, Clock clock, MeterRegistry registry) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.props = props;
        this.clock = clock;
        this.published = Counter.builder("outbox.published").description("Messages confirmed by the broker").register(registry);
        this.failures = Counter.builder("outbox.publish.failures").description("Sends that were not confirmed").register(registry);
        // A growing value means Kafka is unreachable or the publisher is stuck.
        Gauge.builder("outbox.pending", repository, OutboxRepository::countByPublishedAtIsNull)
                .description("Messages waiting to be published").register(registry);
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:200}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = repository.claimUnpublished(props.batchSize());
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload())
                        .get(props.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                event.setPublishedAt(clock.instant());
                published.increment();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException e) {
                failures.increment();
                log.warn("Publish not confirmed, will retry on the next poll messageId={} topic={}",
                        event.getMessageId(), event.getTopic(), e);
                return;
            }
        }
    }
}
