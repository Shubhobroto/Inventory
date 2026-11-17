package com.resilient.orders.saga;

import com.resilient.orders.api.CreateOrderRequest;
import com.resilient.orders.domain.OrderStatus;
import com.resilient.orders.domain.PurchaseOrder;
import com.resilient.orders.domain.SagaStatus;
import com.resilient.orders.messaging.Envelope;
import com.resilient.orders.messaging.MessageCodec;
import com.resilient.orders.messaging.Messages.InventoryRejected;
import com.resilient.orders.messaging.Messages.InventoryReleased;
import com.resilient.orders.messaging.Messages.InventoryReserved;
import com.resilient.orders.messaging.Messages.PaymentCompleted;
import com.resilient.orders.messaging.Messages.PaymentFailed;
import com.resilient.orders.outbox.OutboxEvent;
import com.resilient.orders.outbox.OutboxRepository;
import com.resilient.orders.repository.PurchaseOrderRepository;
import com.resilient.orders.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole saga from this service's side, with the real Spring wiring, transactions and database (H2) but
 * no Kafka: each "reply" from the other services is handed to the orchestrator directly.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:orders-flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "outbox.poll-interval-ms=3600000",
        "outbox.cleanup-interval-ms=3600000",
        "saga.timeout-check-interval-ms=3600000",
        "saga.timeout=0s"})
class OrderSagaFlowTest {

    @Autowired
    OrderService orders;
    @Autowired
    SagaOrchestrator saga;
    @Autowired
    MessageCodec codec;
    @Autowired
    PurchaseOrderRepository orderRepository;
    @Autowired
    OutboxRepository outbox;

    private PurchaseOrder newOrder() {
        return orders.create(UUID.randomUUID().toString(),
                new CreateOrderRequest("CUST-1", "SKU-1", 2, new BigDecimal("25.00"))).order();
    }

    private Envelope reply(PurchaseOrder order, Object payload) {
        return codec.wrap(order.getId().toString(), payload, Instant.now());
    }

    private PurchaseOrder reload(PurchaseOrder order) {
        return orderRepository.findById(order.getId()).orElseThrow();
    }

    /** Message types written to the outbox for this order, in the order they were written. */
    private List<String> sent(PurchaseOrder order) {
        return outbox.findAll(Sort.by("id")).stream()
                .filter(e -> e.getMessageKey().equals(order.getId().toString()))
                .map(OutboxEvent::getEventType)
                .toList();
    }

    @Test
    void happyPathReservesThenChargesThenConfirms() {
        PurchaseOrder order = newOrder();
        String id = order.getId().toString();
        assertThat(sent(order)).containsExactly("ReserveInventory");

        saga.handle(reply(order, new InventoryReserved(id)));
        assertThat(reload(order).getSagaStatus()).isEqualTo(SagaStatus.AWAITING_PAYMENT);
        assertThat(sent(order)).containsExactly("ReserveInventory", "ChargeCustomer");

        saga.handle(reply(order, new PaymentCompleted(id)));
        PurchaseOrder done = reload(order);
        assertThat(done.getSagaStatus()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(done.getOrderStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void outOfStockRejectsTheOrderAndNeverCharges() {
        PurchaseOrder order = newOrder();

        saga.handle(reply(order, new InventoryRejected(order.getId().toString(), "INSUFFICIENT_STOCK")));

        PurchaseOrder rejected = reload(order);
        assertThat(rejected.getOrderStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(rejected.getFailureReason()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(sent(order)).containsExactly("ReserveInventory");
    }

    @Test
    void aFailedPaymentReleasesTheStockAndCancelsTheOrder() {
        PurchaseOrder order = newOrder();
        String id = order.getId().toString();
        saga.handle(reply(order, new InventoryReserved(id)));

        saga.handle(reply(order, new PaymentFailed(id, "INSUFFICIENT_FUNDS")));
        assertThat(reload(order).getSagaStatus()).isEqualTo(SagaStatus.COMPENSATING);
        assertThat(sent(order)).containsExactly("ReserveInventory", "ChargeCustomer", "ReleaseInventory");

        saga.handle(reply(order, new InventoryReleased(id)));
        PurchaseOrder cancelled = reload(order);
        assertThat(cancelled.getSagaStatus()).isEqualTo(SagaStatus.FAILED);
        assertThat(cancelled.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void aRedeliveredReplyDoesNotTriggerTheNextStepTwice() {
        PurchaseOrder order = newOrder();
        Envelope reserved = reply(order, new InventoryReserved(order.getId().toString()));

        saga.handle(reserved);
        saga.handle(reserved);

        assertThat(sent(order)).containsExactly("ReserveInventory", "ChargeCustomer");
    }

    @Test
    void aSecondReplyWithANewMessageIdIsStillIgnoredBecauseTheOrderMovedOn() {
        PurchaseOrder order = newOrder();
        String id = order.getId().toString();

        saga.handle(reply(order, new InventoryReserved(id)));
        saga.handle(reply(order, new InventoryReserved(id)));

        assertThat(sent(order)).containsExactly("ReserveInventory", "ChargeCustomer");
    }

    @Test
    void theSameIdempotencyKeyReturnsTheSameOrderAndWritesOneCommand() {
        String key = UUID.randomUUID().toString();
        CreateOrderRequest request = new CreateOrderRequest("CUST-1", "SKU-1", 2, new BigDecimal("25.00"));

        PurchaseOrder first = orders.create(key, request).order();
        OrderService.CreateResult second = orders.create(key, request);

        assertThat(second.created()).isFalse();
        assertThat(second.order().getId()).isEqualTo(first.getId());
        assertThat(sent(first)).containsExactly("ReserveInventory");
    }

    @Test
    void anOrderThatTimesOutIsCancelledCompensatedAndIgnoresLateReplies() throws InterruptedException {
        PurchaseOrder order = newOrder();
        Thread.sleep(25);

        saga.failStuckOrders();

        PurchaseOrder timedOut = reload(order);
        assertThat(timedOut.getSagaStatus()).isEqualTo(SagaStatus.FAILED);
        assertThat(timedOut.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(timedOut.getFailureReason()).isEqualTo("TIMEOUT");
        assertThat(sent(order)).containsExactly("ReserveInventory", "ReleaseInventory", "RefundCustomer");

        // the inventory reply that was already on its way must not revive the order
        saga.handle(reply(order, new InventoryReserved(order.getId().toString())));
        assertThat(reload(order).getSagaStatus()).isEqualTo(SagaStatus.FAILED);
        assertThat(sent(order)).hasSize(3);
    }
}
