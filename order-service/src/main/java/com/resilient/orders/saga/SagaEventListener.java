package com.resilient.orders.saga;

import com.resilient.orders.messaging.MessageCodec;
import com.resilient.orders.messaging.Topics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SagaEventListener {

    private final SagaOrchestrator orchestrator;
    private final MessageCodec codec;

    public SagaEventListener(SagaOrchestrator orchestrator, MessageCodec codec) {
        this.orchestrator = orchestrator;
        this.codec = codec;
    }

    // A failure here is retried with backoff, then the message goes to <topic>.DLT (see KafkaConfig).
    @KafkaListener(topics = {Topics.INVENTORY_EVENTS, Topics.PAYMENT_EVENTS}, groupId = "order-service")
    public void onMessage(String value) {
        orchestrator.handle(codec.parse(value));
    }
}
