package com.resilient.inventory.handler;

import com.resilient.inventory.messaging.MessageCodec;
import com.resilient.inventory.messaging.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryCommandListener {

    private final InventoryCommandHandler handler;
    private final MessageCodec codec;

    public InventoryCommandListener(InventoryCommandHandler handler, MessageCodec codec) {
        this.handler = handler;
        this.codec = codec;
    }

    // A failure is retried with backoff, then the message goes to inventory.commands.DLT (see KafkaConfig).
    @KafkaListener(topics = Topics.INVENTORY_COMMANDS, groupId = "inventory-service")
    public void onMessage(String value) {
        handler.handle(codec.parse(value));
    }
}
