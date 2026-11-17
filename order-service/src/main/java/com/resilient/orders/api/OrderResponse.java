package com.resilient.orders.api;

import com.resilient.orders.domain.OrderStatus;
import com.resilient.orders.domain.PurchaseOrder;
import com.resilient.orders.domain.SagaStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        OrderStatus status,
        SagaStatus sagaStatus,
        String failureReason,
        String customerId,
        String productId,
        int quantity,
        BigDecimal amount,
        Instant createdAt,
        Instant updatedAt) {

    public static OrderResponse from(PurchaseOrder o) {
        return new OrderResponse(o.getId(), o.getOrderStatus(), o.getSagaStatus(), o.getFailureReason(),
                o.getCustomerId(), o.getProductId(), o.getQuantity(), o.getAmount(), o.getCreatedAt(), o.getUpdatedAt());
    }
}
