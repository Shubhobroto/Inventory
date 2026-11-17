package com.resilient.orders.messaging;

import java.math.BigDecimal;

/**
 * The message contract between the three services. Each service keeps its own copy so they can be built and
 * deployed independently; the message type is the record's simple name. Commands go to a service, events come back.
 */
public final class Messages {

    // commands
    public record ReserveInventory(String orderId, String productId, int quantity) {
    }

    public record ReleaseInventory(String orderId) {
    }

    public record ChargeCustomer(String orderId, String customerId, BigDecimal amount) {
    }

    public record RefundCustomer(String orderId) {
    }

    // events
    public record InventoryReserved(String orderId) {
    }

    public record InventoryRejected(String orderId, String reason) {
    }

    public record InventoryReleased(String orderId) {
    }

    public record PaymentCompleted(String orderId) {
    }

    public record PaymentFailed(String orderId, String reason) {
    }

    public record PaymentRefunded(String orderId) {
    }

    private Messages() {
    }
}
