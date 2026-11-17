package com.resilient.orders.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Named PurchaseOrder because ORDER is a reserved word in SQL and HQL.
@Getter
@NoArgsConstructor
@Entity
@Table(name = "orders")
public class PurchaseOrder {

    @Id
    private UUID id;

    private String idempotencyKey;
    private String customerId;
    private String productId;
    private int quantity;
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private OrderStatus orderStatus;

    @Enumerated(EnumType.STRING)
    private SagaStatus sagaStatus;

    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;

    public static PurchaseOrder create(String idempotencyKey, String customerId, String productId,
                                       int quantity, BigDecimal amount, Instant now) {
        PurchaseOrder order = new PurchaseOrder();
        order.id = UUID.randomUUID();
        order.idempotencyKey = idempotencyKey;
        order.customerId = customerId;
        order.productId = productId;
        order.quantity = quantity;
        order.amount = amount;
        order.orderStatus = OrderStatus.PENDING;
        order.sagaStatus = SagaStatus.AWAITING_INVENTORY;
        order.createdAt = now;
        order.updatedAt = now;
        return order;
    }
}
