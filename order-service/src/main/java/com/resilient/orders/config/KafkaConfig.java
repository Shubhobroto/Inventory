package com.resilient.orders.config;

import com.resilient.orders.messaging.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {

    // Three partitions, keyed by order id: orders spread across consumers, one order stays in sequence.
    private static final int PARTITIONS = 3;

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    NewTopic inventoryCommands() {
        return topic(Topics.INVENTORY_COMMANDS);
    }

    @Bean
    NewTopic inventoryEvents() {
        return topic(Topics.INVENTORY_EVENTS);
    }

    @Bean
    NewTopic paymentCommands() {
        return topic(Topics.PAYMENT_COMMANDS);
    }

    @Bean
    NewTopic paymentEvents() {
        return topic(Topics.PAYMENT_EVENTS);
    }

    @Bean
    NewTopic inventoryEventsDlt() {
        return topic(Topics.INVENTORY_EVENTS + Topics.DEAD_LETTER_SUFFIX);
    }

    @Bean
    NewTopic paymentEventsDlt() {
        return topic(Topics.PAYMENT_EVENTS + Topics.DEAD_LETTER_SUFFIX);
    }

    /**
     * Failed messages are retried with backoff (0.5 s, 1 s, 2 s), then parked on a dead-letter topic
     * so one bad message never blocks the partition. Unreadable messages skip the retries.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + Topics.DEAD_LETTER_SUFFIX, record.partition()));
        ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxAttempts(3);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
