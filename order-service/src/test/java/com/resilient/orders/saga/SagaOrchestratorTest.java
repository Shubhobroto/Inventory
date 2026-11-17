package com.resilient.orders.saga;

import com.resilient.orders.config.SagaProperties;
import com.resilient.orders.domain.OrderStatus;
import com.resilient.orders.domain.PurchaseOrder;
import com.resilient.orders.domain.SagaStatus;
import com.resilient.orders.inbox.InboxGuard;
import com.resilient.orders.messaging.Envelope;
import com.resilient.orders.messaging.MessageCodec;
import com.resilient.orders.messaging.Messages.ChargeCustomer;
import com.resilient.orders.messaging.Messages.InventoryRejected;
import com.resilient.orders.messaging.Messages.InventoryReleased;
import com.resilient.orders.messaging.Messages.InventoryReserved;
import com.resilient.orders.messaging.Messages.PaymentCompleted;
import com.resilient.orders.messaging.Messages.PaymentFailed;
import com.resilient.orders.messaging.Messages.RefundCustomer;
import com.resilient.orders.messaging.Messages.ReleaseInventory;
import com.resilient.orders.messaging.Topics;
import com.resilient.orders.outbox.OutboxWriter;
import com.resilient.orders.repository.PurchaseOrderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SagaOrchestratorTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:10:00Z");

    private final PurchaseOrderRepository orders = mock(PurchaseOrderRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final InboxGuard inbox = mock(InboxGuard.class);
    private final MessageCodec codec = new MessageCodec();
    private SagaOrchestrator saga;

    private final PurchaseOrder order = PurchaseOrder.create("k", "c1", "p1", 2, new BigDecimal("25.00"), NOW.minusSeconds(5));
    private final String id = order.getId().toString();

    @BeforeEach
    void setUp() {
        when(inbox.firstTime(any())).thenReturn(true);
        saga = new SagaOrchestrator(orders, outbox, inbox, codec, new SagaProperties(Duration.ofSeconds(60), 50),
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());
    }

    private Envelope message(Object payload) {
        return codec.wrap(id, payload, NOW);
    }

    private void transitionReturns(SagaStatus from, SagaStatus to, OrderStatus status, String reason, int rows) {
        when(orders.transition(order.getId(), from, to, status, reason, NOW)).thenReturn(rows);
    }

    @Test
    void reservedStockMovesTheOrderToPaymentAndSendsTheCharge() {
        transitionReturns(SagaStatus.AWAITING_INVENTORY, SagaStatus.AWAITING_PAYMENT, OrderStatus.PENDING, null, 1);
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));

        saga.handle(message(new InventoryReserved(id)));

        verify(outbox).write(Topics.PAYMENT_COMMANDS, id, new ChargeCustomer(id, "c1", new BigDecimal("25.00")));
    }

    @Test
    void aRedeliveredMessageDoesNothing() {
        when(inbox.firstTime(any())).thenReturn(false);

        saga.handle(message(new InventoryReserved(id)));

        verifyNoInteractions(orders, outbox);
    }

    @Test
    void aLateMessageForAnOrderThatMovedOnSendsNothing() {
        transitionReturns(SagaStatus.AWAITING_INVENTORY, SagaStatus.AWAITING_PAYMENT, OrderStatus.PENDING, null, 0);

        saga.handle(message(new InventoryReserved(id)));

        verify(outbox, never()).write(any(), any(), any());
    }

    @Test
    void rejectedStockEndsTheOrderAsRejected() {
        transitionReturns(SagaStatus.AWAITING_INVENTORY, SagaStatus.FAILED, OrderStatus.REJECTED, "INSUFFICIENT_STOCK", 1);

        saga.handle(message(new InventoryRejected(id, "INSUFFICIENT_STOCK")));

        verify(orders).transition(order.getId(), SagaStatus.AWAITING_INVENTORY, SagaStatus.FAILED,
                OrderStatus.REJECTED, "INSUFFICIENT_STOCK", NOW);
        verify(outbox, never()).write(any(), any(), any());
    }

    @Test
    void aSuccessfulPaymentConfirmsTheOrder() {
        transitionReturns(SagaStatus.AWAITING_PAYMENT, SagaStatus.COMPLETED, OrderStatus.CONFIRMED, null, 1);

        saga.handle(message(new PaymentCompleted(id)));

        verify(orders).transition(order.getId(), SagaStatus.AWAITING_PAYMENT, SagaStatus.COMPLETED,
                OrderStatus.CONFIRMED, null, NOW);
    }

    @Test
    void aFailedPaymentStartsCompensationByReleasingTheStock() {
        transitionReturns(SagaStatus.AWAITING_PAYMENT, SagaStatus.COMPENSATING, OrderStatus.PENDING, "INSUFFICIENT_FUNDS", 1);

        saga.handle(message(new PaymentFailed(id, "INSUFFICIENT_FUNDS")));

        verify(outbox).write(Topics.INVENTORY_COMMANDS, id, new ReleaseInventory(id));
    }

    @Test
    void aDuplicatePaymentFailureDoesNotReleaseTwice() {
        transitionReturns(SagaStatus.AWAITING_PAYMENT, SagaStatus.COMPENSATING, OrderStatus.PENDING, "INSUFFICIENT_FUNDS", 0);

        saga.handle(message(new PaymentFailed(id, "INSUFFICIENT_FUNDS")));

        verify(outbox, never()).write(any(), any(), any());
    }

    @Test
    void releasedStockFinishesTheCompensationAndKeepsTheFailureReason() {
        PurchaseOrder compensating = mock(PurchaseOrder.class);
        when(compensating.getFailureReason()).thenReturn("INSUFFICIENT_FUNDS");
        when(compensating.getCreatedAt()).thenReturn(NOW.minusSeconds(5));
        when(orders.findById(order.getId())).thenReturn(Optional.of(compensating));
        transitionReturns(SagaStatus.COMPENSATING, SagaStatus.FAILED, OrderStatus.CANCELLED, "INSUFFICIENT_FUNDS", 1);

        saga.handle(message(new InventoryReleased(id)));

        verify(orders).transition(order.getId(), SagaStatus.COMPENSATING, SagaStatus.FAILED,
                OrderStatus.CANCELLED, "INSUFFICIENT_FUNDS", NOW);
    }

    @Test
    void anUnknownMessageTypeIsIgnored() {
        saga.handle(message(new RefundCustomer(id)));

        verify(outbox, never()).write(any(), any(), any());
    }

    @Test
    void aTimedOutOrderIsCancelledAndBothStepsAreCompensated() {
        when(orders.findStuck(SagaStatus.IN_PROGRESS, NOW.minusSeconds(60), PageRequest.of(0, 50))).thenReturn(List.of(order));
        transitionReturns(SagaStatus.AWAITING_INVENTORY, SagaStatus.FAILED, OrderStatus.CANCELLED, "TIMEOUT", 1);

        int failed = saga.failStuckOrders();

        assertThat(failed).isEqualTo(1);
        verify(outbox).write(Topics.INVENTORY_COMMANDS, id, new ReleaseInventory(id));
        verify(outbox).write(Topics.PAYMENT_COMMANDS, id, new RefundCustomer(id));
    }

    @Test
    void anOrderAnotherInstanceAlreadyHandledIsSkippedByTheTimeoutSweep() {
        when(orders.findStuck(SagaStatus.IN_PROGRESS, NOW.minusSeconds(60), PageRequest.of(0, 50))).thenReturn(List.of(order));
        transitionReturns(SagaStatus.AWAITING_INVENTORY, SagaStatus.FAILED, OrderStatus.CANCELLED, "TIMEOUT", 0);

        assertThat(saga.failStuckOrders()).isZero();
        verify(outbox, never()).write(any(), any(), any());
    }

    @Test
    void theIdOfTheMessageIsUsedForDeduplication() {
        Envelope envelope = message(new PaymentCompleted(id));
        transitionReturns(SagaStatus.AWAITING_PAYMENT, SagaStatus.COMPLETED, OrderStatus.CONFIRMED, null, 1);

        saga.handle(envelope);

        verify(inbox).firstTime(envelope.messageId());
        assertThat(envelope.messageId()).isInstanceOf(UUID.class);
    }
}
