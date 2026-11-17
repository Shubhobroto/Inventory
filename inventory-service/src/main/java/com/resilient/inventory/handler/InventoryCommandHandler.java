package com.resilient.inventory.handler;

import com.resilient.inventory.domain.Reservation;
import com.resilient.inventory.domain.ReservationStatus;
import com.resilient.inventory.inbox.InboxGuard;
import com.resilient.inventory.messaging.Envelope;
import com.resilient.inventory.messaging.MessageCodec;
import com.resilient.inventory.messaging.Messages.InventoryRejected;
import com.resilient.inventory.messaging.Messages.InventoryReleased;
import com.resilient.inventory.messaging.Messages.InventoryReserved;
import com.resilient.inventory.messaging.Messages.ReleaseInventory;
import com.resilient.inventory.messaging.Messages.ReserveInventory;
import com.resilient.inventory.messaging.Topics;
import com.resilient.inventory.outbox.OutboxWriter;
import com.resilient.inventory.repository.ReservationRepository;
import com.resilient.inventory.repository.StockRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles reserve and release commands. For each message the stock change, the reservation row, the
 * inbox entry and the reply (outbox) are one database transaction.
 *
 * Commands may arrive twice, or out of order (a release can overtake the reserve it belongs to).
 * The reservation row per order makes both safe: a repeated command gets the same answer, and a release
 * that arrives first leaves a marker so the late reserve is refused.
 */
@Slf4j
@Service
public class InventoryCommandHandler {

    static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
    static final String CANCELLED = "CANCELLED";

    private final StockRepository stock;
    private final ReservationRepository reservations;
    private final OutboxWriter outbox;
    private final InboxGuard inbox;
    private final MessageCodec codec;
    private final Clock clock;

    public InventoryCommandHandler(StockRepository stock, ReservationRepository reservations, OutboxWriter outbox,
                                   InboxGuard inbox, MessageCodec codec, Clock clock) {
        this.stock = stock;
        this.reservations = reservations;
        this.outbox = outbox;
        this.inbox = inbox;
        this.codec = codec;
        this.clock = clock;
    }

    @Transactional
    public void handle(Envelope envelope) {
        if (!inbox.firstTime(envelope.messageId())) {
            log.info("Duplicate message ignored type={} messageId={}", envelope.type(), envelope.messageId());
            return;
        }
        switch (envelope.type()) {
            case "ReserveInventory" -> reserve(codec.payload(envelope, ReserveInventory.class));
            case "ReleaseInventory" -> release(codec.payload(envelope, ReleaseInventory.class));
            default -> log.debug("Ignoring message type {}", envelope.type());
        }
    }

    private void reserve(ReserveInventory command) {
        UUID orderId = UUID.fromString(command.orderId());
        Optional<Reservation> existing = reservations.findById(orderId);
        if (existing.isPresent()) {
            repeatAnswer(existing.get());
            return;
        }
        Instant now = clock.instant();
        if (command.quantity() > 0 && stock.reserve(command.productId(), command.quantity()) == 1) {
            reservations.save(Reservation.reserved(orderId, command.productId(), command.quantity(), now));
            reply(command.orderId(), new InventoryReserved(command.orderId()));
        } else {
            reservations.save(Reservation.rejected(orderId, command.productId(), command.quantity(), now));
            reply(command.orderId(), new InventoryRejected(command.orderId(), INSUFFICIENT_STOCK));
        }
    }

    private void release(ReleaseInventory command) {
        UUID orderId = UUID.fromString(command.orderId());
        Optional<Reservation> existing = reservations.findById(orderId);
        Instant now = clock.instant();
        if (existing.isEmpty()) {
            reservations.save(Reservation.cancelledBeforeReserved(orderId, now));
        } else if (existing.get().getStatus() == ReservationStatus.RESERVED) {
            Reservation reservation = existing.get();
            stock.release(reservation.getProductId(), reservation.getQuantity());
            reservation.markReleased(now);
        }
        // REJECTED or already RELEASED: nothing to give back.
        reply(command.orderId(), new InventoryReleased(command.orderId()));
    }

    private void repeatAnswer(Reservation reservation) {
        String orderId = reservation.getOrderId().toString();
        switch (reservation.getStatus()) {
            case RESERVED -> reply(orderId, new InventoryReserved(orderId));
            case REJECTED -> reply(orderId, new InventoryRejected(orderId, INSUFFICIENT_STOCK));
            case RELEASED -> reply(orderId, new InventoryRejected(orderId, CANCELLED));
        }
    }

    private void reply(String orderId, Object event) {
        outbox.write(Topics.INVENTORY_EVENTS, orderId, event);
    }
}
