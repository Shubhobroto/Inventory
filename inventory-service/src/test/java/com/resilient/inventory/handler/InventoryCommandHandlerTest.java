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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InventoryCommandHandlerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final StockRepository stock = mock(StockRepository.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final InboxGuard inbox = mock(InboxGuard.class);
    private final MessageCodec codec = new MessageCodec();
    private InventoryCommandHandler handler;

    private final UUID orderUuid = UUID.randomUUID();
    private final String orderId = orderUuid.toString();

    @BeforeEach
    void setUp() {
        when(inbox.firstTime(any())).thenReturn(true);
        when(reservations.findById(orderUuid)).thenReturn(Optional.empty());
        handler = new InventoryCommandHandler(stock, reservations, outbox, inbox, codec, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Envelope message(Object payload) {
        return codec.wrap(orderId, payload, NOW);
    }

    private ReserveInventory reserve(int quantity) {
        return new ReserveInventory(orderId, "SKU-1", quantity);
    }

    @Test
    void reservesStockAndRepliesReservedWhenEnoughIsLeft() {
        when(stock.reserve("SKU-1", 2)).thenReturn(1);

        handler.handle(message(reserve(2)));

        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
        verify(reservations).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ReservationStatus.RESERVED);
        verify(outbox).write(Topics.INVENTORY_EVENTS, orderId, new InventoryReserved(orderId));
    }

    @Test
    void repliesRejectedAndRemembersItWhenThereIsNotEnoughStock() {
        when(stock.reserve("SKU-1", 2)).thenReturn(0);

        handler.handle(message(reserve(2)));

        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
        verify(reservations).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ReservationStatus.REJECTED);
        verify(outbox).write(Topics.INVENTORY_EVENTS, orderId, new InventoryRejected(orderId, "INSUFFICIENT_STOCK"));
    }

    @Test
    void aQuantityOfZeroNeverTouchesStock() {
        handler.handle(message(reserve(0)));

        verify(stock, never()).reserve(anyString(), anyInt());
        verify(outbox).write(Topics.INVENTORY_EVENTS, orderId, new InventoryRejected(orderId, "INSUFFICIENT_STOCK"));
    }

    @Test
    void aRepeatedReserveCommandGetsTheSameAnswerAndTakesNoMoreStock() {
        when(reservations.findById(orderUuid)).thenReturn(Optional.of(Reservation.reserved(orderUuid, "SKU-1", 2, NOW)));

        handler.handle(message(reserve(2)));

        verify(stock, never()).reserve(anyString(), anyInt());
        verify(outbox).write(Topics.INVENTORY_EVENTS, orderId, new InventoryReserved(orderId));
    }

    @Test
    void aReserveThatArrivesAfterItsReleaseIsRefused() {
        when(reservations.findById(orderUuid)).thenReturn(Optional.of(Reservation.cancelledBeforeReserved(orderUuid, NOW)));

        handler.handle(message(reserve(2)));

        verify(stock, never()).reserve(anyString(), anyInt());
        verify(outbox).write(Topics.INVENTORY_EVENTS, orderId, new InventoryRejected(orderId, "CANCELLED"));
    }

    @Test
    void releaseGivesTheReservedStockBack() {
        Reservation reservation = Reservation.reserved(orderUuid, "SKU-1", 2, NOW);
        when(reservations.findById(orderUuid)).thenReturn(Optional.of(reservation));

        handler.handle(message(new ReleaseInventory(orderId)));

        verify(stock).release("SKU-1", 2);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        verify(outbox).write(Topics.INVENTORY_EVENTS, orderId, new InventoryReleased(orderId));
    }

    @Test
    void releasingTwiceGivesTheStockBackOnlyOnce() {
        Reservation reservation = Reservation.reserved(orderUuid, "SKU-1", 2, NOW);
        reservation.markReleased(NOW);
        when(reservations.findById(orderUuid)).thenReturn(Optional.of(reservation));

        handler.handle(message(new ReleaseInventory(orderId)));

        verify(stock, never()).release(anyString(), anyInt());
        verify(outbox).write(Topics.INVENTORY_EVENTS, orderId, new InventoryReleased(orderId));
    }

    @Test
    void aReleaseForAnUnknownOrderLeavesAMarkerSoALateReserveIsRefused() {
        handler.handle(message(new ReleaseInventory(orderId)));

        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
        verify(reservations).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ReservationStatus.RELEASED);
        verify(stock, never()).release(anyString(), anyInt());
        verify(outbox).write(Topics.INVENTORY_EVENTS, orderId, new InventoryReleased(orderId));
    }

    @Test
    void aRedeliveredMessageIsIgnored() {
        when(inbox.firstTime(any())).thenReturn(false);

        handler.handle(message(reserve(2)));

        verifyNoInteractions(stock, reservations, outbox);
    }
}
