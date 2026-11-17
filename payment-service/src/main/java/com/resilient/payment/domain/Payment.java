package com.resilient.payment.domain;

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

/** One row per order: the order id as primary key means an order is charged at most once. */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID orderId;

    private String customerId;
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    private Instant createdAt;
    private Instant updatedAt;

    private Payment(UUID orderId, String customerId, BigDecimal amount, PaymentStatus status, Instant now) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount;
        this.status = status;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Payment charged(UUID orderId, String customerId, BigDecimal amount, Instant now) {
        return new Payment(orderId, customerId, amount, PaymentStatus.CHARGED, now);
    }

    public static Payment failed(UUID orderId, String customerId, BigDecimal amount, Instant now) {
        return new Payment(orderId, customerId, amount, PaymentStatus.FAILED, now);
    }

    public static Payment cancelledBeforeCharged(UUID orderId, Instant now) {
        return new Payment(orderId, null, BigDecimal.ZERO, PaymentStatus.CANCELLED, now);
    }

    public void markRefunded(Instant now) {
        this.status = PaymentStatus.REFUNDED;
        this.updatedAt = now;
    }
}
