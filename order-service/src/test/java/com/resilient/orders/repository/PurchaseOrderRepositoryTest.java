package com.resilient.orders.repository;

import com.resilient.orders.domain.OrderStatus;
import com.resilient.orders.domain.PurchaseOrder;
import com.resilient.orders.domain.SagaStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PurchaseOrderRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:10:00Z");

    @Autowired
    PurchaseOrderRepository repository;

    private PurchaseOrder order(String key, Instant at) {
        return repository.saveAndFlush(PurchaseOrder.create(key, "c1", "p1", 1, new BigDecimal("10.00"), at));
    }

    @Test
    void transitionChangesTheOrderOnlyFromTheExpectedState() {
        PurchaseOrder order = order("k1", NOW);

        int first = repository.transition(order.getId(), SagaStatus.AWAITING_INVENTORY, SagaStatus.AWAITING_PAYMENT,
                OrderStatus.PENDING, null, NOW);
        int second = repository.transition(order.getId(), SagaStatus.AWAITING_INVENTORY, SagaStatus.AWAITING_PAYMENT,
                OrderStatus.PENDING, null, NOW);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(repository.findById(order.getId()).orElseThrow().getSagaStatus()).isEqualTo(SagaStatus.AWAITING_PAYMENT);
    }

    @Test
    void transitionRecordsTheFinalStatusAndReason() {
        PurchaseOrder order = order("k1", NOW);

        repository.transition(order.getId(), SagaStatus.AWAITING_INVENTORY, SagaStatus.FAILED,
                OrderStatus.REJECTED, "INSUFFICIENT_STOCK", NOW);

        PurchaseOrder loaded = repository.findById(order.getId()).orElseThrow();
        assertThat(loaded.getOrderStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(loaded.getFailureReason()).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    void findStuckReturnsOnlyIdleOrdersThatAreStillInProgress() {
        PurchaseOrder idle = order("idle", NOW.minusSeconds(300));
        order("recent", NOW.minusSeconds(5));
        PurchaseOrder finished = order("finished", NOW.minusSeconds(300));
        repository.transition(finished.getId(), SagaStatus.AWAITING_INVENTORY, SagaStatus.COMPLETED,
                OrderStatus.CONFIRMED, null, NOW.minusSeconds(300));

        List<PurchaseOrder> stuck = repository.findStuck(SagaStatus.IN_PROGRESS, NOW.minusSeconds(60), PageRequest.of(0, 10));

        assertThat(stuck).extracting(PurchaseOrder::getId).containsExactly(idle.getId());
    }

    @Test
    void theDatabaseRejectsASecondOrderWithTheSameIdempotencyKey() {
        order("same-key", NOW);

        assertThrows(DataIntegrityViolationException.class, () -> order("same-key", NOW));
    }

    @Test
    void findsAnOrderByItsIdempotencyKey() {
        PurchaseOrder saved = order("find-me", NOW);

        assertThat(repository.findByIdempotencyKey("find-me")).map(PurchaseOrder::getId).contains(saved.getId());
    }
}
