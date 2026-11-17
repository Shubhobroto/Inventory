package com.resilient.inventory.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per order. The order id is the primary key, so an order can never reserve stock twice,
 * and the row also remembers the answer given, which makes the commands safe to repeat.
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    private UUID orderId;

    // Empty on a "cancelled before reserved" marker.
    private String productId;
    private int quantity;

    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    private Instant createdAt;
    private Instant updatedAt;

    private Reservation(UUID orderId, String productId, int quantity, ReservationStatus status, Instant now) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.status = status;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Reservation reserved(UUID orderId, String productId, int quantity, Instant now) {
        return new Reservation(orderId, productId, quantity, ReservationStatus.RESERVED, now);
    }

    public static Reservation rejected(UUID orderId, String productId, int quantity, Instant now) {
        return new Reservation(orderId, productId, quantity, ReservationStatus.REJECTED, now);
    }

    /** The release command arrived before the reserve command: remember it so the late reserve is refused. */
    public static Reservation cancelledBeforeReserved(UUID orderId, Instant now) {
        return new Reservation(orderId, null, 0, ReservationStatus.RELEASED, now);
    }

    public void markReleased(Instant now) {
        this.status = ReservationStatus.RELEASED;
        this.updatedAt = now;
    }
}
