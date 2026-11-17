package com.resilient.payment.config;

import com.resilient.payment.messaging.Topics;
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

    private static final int PARTITIONS = 3;

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build();
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
    NewTopic paymentCommandsDlt() {
        return topic(Topics.PAYMENT_COMMANDS + Topics.DEAD_LETTER_SUFFIX);
    }

    /** Retry with backoff, then park the message on the dead-letter topic; unreadable messages skip the retries. */
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
