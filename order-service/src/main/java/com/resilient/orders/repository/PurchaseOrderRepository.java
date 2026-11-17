package com.resilient.orders.repository;

import com.resilient.orders.domain.OrderStatus;
import com.resilient.orders.domain.PurchaseOrder;
import com.resilient.orders.domain.SagaStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    Optional<PurchaseOrder> findByIdempotencyKey(String idempotencyKey);

    /**
     * Compare-and-set on the saga state: the row changes only if it is still in the expected state.
     * Returns 0 for a duplicate, late or racing message, so the caller does nothing.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE PurchaseOrder o
            SET o.sagaStatus = :to, o.orderStatus = :orderStatus, o.failureReason = :reason, o.updatedAt = :now
            WHERE o.id = :id AND o.sagaStatus = :from
            """)
    int transition(@Param("id") UUID id,
                   @Param("from") SagaStatus from,
                   @Param("to") SagaStatus to,
                   @Param("orderStatus") OrderStatus orderStatus,
                   @Param("reason") String reason,
                   @Param("now") Instant now);

    @Query("""
            SELECT o FROM PurchaseOrder o
            WHERE o.sagaStatus IN :statuses AND o.updatedAt < :cutoff
            ORDER BY o.updatedAt
            """)
    List<PurchaseOrder> findStuck(@Param("statuses") Collection<SagaStatus> statuses,
                                  @Param("cutoff") Instant cutoff,
                                  Pageable page);
}
