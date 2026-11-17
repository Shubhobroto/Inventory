package com.resilient.payment.handler;

import com.resilient.payment.messaging.MessageCodec;
import com.resilient.payment.messaging.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentCommandListener {

    private final PaymentCommandHandler handler;
    private final MessageCodec codec;

    public PaymentCommandListener(PaymentCommandHandler handler, MessageCodec codec) {
        this.handler = handler;
        this.codec = codec;
    }

    // A failure is retried with backoff, then the message goes to payment.commands.DLT (see KafkaConfig).
    @KafkaListener(topics = Topics.PAYMENT_COMMANDS, groupId = "payment-service")
    public void onMessage(String value) {
        handler.handle(codec.parse(value));
    }
}
