package com.resilient.inventory.handler;

import com.resilient.inventory.messaging.Envelope;
import com.resilient.inventory.messaging.MessageCodec;
import com.resilient.inventory.messaging.Messages.ReleaseInventory;
import com.resilient.inventory.messaging.Messages.ReserveInventory;
import com.resilient.inventory.outbox.OutboxEvent;
import com.resilient.inventory.outbox.OutboxRepository;
import com.resilient.inventory.repository.StockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Spring wiring, transactions and database (H2), no Kafka: commands are handed to the handler directly. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:inventory-flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "outbox.poll-interval-ms=3600000",
        "outbox.cleanup-interval-ms=3600000"})
class InventoryFlowTest {

    @Autowired
    InventoryCommandHandler handler;
    @Autowired
    MessageCodec codec;
    @Autowired
    StockRepository stock;
    @Autowired
    OutboxRepository outbox;

    private int available(String productId) {
        return stock.findById(productId).orElseThrow().getAvailable();
    }

    private Envelope command(String orderId, Object payload) {
        return codec.wrap(orderId, payload, Instant.now());
    }

    private List<String> replies(String orderId) {
        return outbox.findAll(Sort.by("id")).stream()
                .filter(e -> e.getMessageKey().equals(orderId))
                .map(OutboxEvent::getEventType)
                .toList();
    }

    @Test
    void reservingTakesStockOnceEvenIfTheCommandIsSentTwice() {
        String orderId = UUID.randomUUID().toString();
        int before = available("SKU-1");

        handler.handle(command(orderId, new ReserveInventory(orderId, "SKU-1", 3)));
        // a duplicate command with a new message id: the order id is the natural key
        handler.handle(command(orderId, new ReserveInventory(orderId, "SKU-1", 3)));

        assertThat(available("SKU-1")).isEqualTo(before - 3);
        assertThat(replies(orderId)).containsExactly("InventoryReserved", "InventoryReserved");
    }

    @Test
    void theSameMessageDeliveredTwiceIsProcessedOnce() {
        String orderId = UUID.randomUUID().toString();
        Envelope reserve = command(orderId, new ReserveInventory(orderId, "SKU-1", 2));
        int before = available("SKU-1");

        handler.handle(reserve);
        handler.handle(reserve);

        assertThat(available("SKU-1")).isEqualTo(before - 2);
        assertThat(replies(orderId)).containsExactly("InventoryReserved");
    }

    @Test
    void notEnoughStockIsRejectedAndStockIsUntouched() {
        String orderId = UUID.randomUUID().toString();
        int before = available("SKU-3");

        handler.handle(command(orderId, new ReserveInventory(orderId, "SKU-3", 1)));

        assertThat(available("SKU-3")).isEqualTo(before);
        assertThat(replies(orderId)).containsExactly("InventoryRejected");
    }

    @Test
    void releaseGivesStockBackOnlyOnce() {
        String orderId = UUID.randomUUID().toString();
        int before = available("SKU-1");
        handler.handle(command(orderId, new ReserveInventory(orderId, "SKU-1", 4)));

        handler.handle(command(orderId, new ReleaseInventory(orderId)));
        handler.handle(command(orderId, new ReleaseInventory(orderId)));

        assertThat(available("SKU-1")).isEqualTo(before);
    }

    @Test
    void aReserveThatArrivesAfterTheReleaseIsRefusedAndTakesNoStock() {
        String orderId = UUID.randomUUID().toString();
        int before = available("SKU-1");

        handler.handle(command(orderId, new ReleaseInventory(orderId)));
        handler.handle(command(orderId, new ReserveInventory(orderId, "SKU-1", 5)));

        assertThat(available("SKU-1")).isEqualTo(before);
        assertThat(replies(orderId)).containsExactly("InventoryReleased", "InventoryRejected");
    }
}
