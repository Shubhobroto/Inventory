package com.resilient.orders.service;

import com.resilient.orders.api.CreateOrderRequest;
import com.resilient.orders.domain.PurchaseOrder;
import com.resilient.orders.domain.SagaStatus;
import com.resilient.orders.messaging.Messages.ReserveInventory;
import com.resilient.orders.messaging.Topics;
import com.resilient.orders.outbox.OutboxWriter;
import com.resilient.orders.repository.PurchaseOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final PurchaseOrderRepository orders = mock(PurchaseOrderRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private OrderService service;

    @BeforeEach
    void setUp() {
        when(transaction.execute(any())).thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        when(orders.save(any())).thenAnswer(call -> call.getArgument(0));
        service = new OrderService(orders, outbox, transaction, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CreateOrderRequest request() {
        return new CreateOrderRequest("c1", "p1", 2, new BigDecimal("25.00"));
    }

    private static PurchaseOrder existingOrder() {
        return PurchaseOrder.create("key-1", "c1", "p1", 2, new BigDecimal("25.00"), NOW);
    }

    @Test
    void createsTheOrderAndItsFirstCommandInOneTransaction() {
        when(orders.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());

        OrderService.CreateResult result = service.create("key-1", request());

        assertThat(result.created()).isTrue();
        assertThat(result.order().getSagaStatus()).isEqualTo(SagaStatus.AWAITING_INVENTORY);
        ArgumentCaptor<ReserveInventory> command = ArgumentCaptor.forClass(ReserveInventory.class);
        verify(outbox).write(eq(Topics.INVENTORY_COMMANDS), eq(result.order().getId().toString()), command.capture());
        assertThat(command.getValue().productId()).isEqualTo("p1");
        assertThat(command.getValue().quantity()).isEqualTo(2);
    }

    @Test
    void repeatingTheSameRequestReturnsTheExistingOrderWithoutNewWork() {
        PurchaseOrder existing = existingOrder();
        when(orders.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        OrderService.CreateResult result = service.create("key-1", request());

        assertThat(result.created()).isFalse();
        assertThat(result.order()).isSameAs(existing);
        verifyNoInteractions(outbox);
    }

    @Test
    void reusingAKeyWithADifferentRequestIsRefused() {
        when(orders.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existingOrder()));
        CreateOrderRequest different = new CreateOrderRequest("c1", "p1", 3, new BigDecimal("25.00"));

        assertThrows(IdempotencyKeyReusedException.class, () -> service.create("key-1", different));
    }

    @Test
    void twoCallsWithTheSameKeyAtTheSameInstantEndUpWithOneOrder() {
        PurchaseOrder winner = existingOrder();
        when(orders.findByIdempotencyKey("key-1")).thenReturn(Optional.empty(), Optional.of(winner));
        doThrow(new DataIntegrityViolationException("duplicate key")).when(transaction).execute(any());

        OrderService.CreateResult result = service.create("key-1", request());

        assertThat(result.created()).isFalse();
        assertThat(result.order()).isSameAs(winner);
    }

    @Test
    void anAmountWithADifferentScaleIsTheSameRequest() {
        when(orders.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existingOrder()));

        OrderService.CreateResult result = service.create("key-1",
                new CreateOrderRequest("c1", "p1", 2, new BigDecimal("25.0")));

        assertThat(result.created()).isFalse();
    }

    @Test
    void aBlankKeyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.create(" ", request()));
    }
}
