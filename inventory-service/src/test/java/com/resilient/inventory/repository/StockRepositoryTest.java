package com.resilient.inventory.repository;

import com.resilient.inventory.domain.Reservation;
import com.resilient.inventory.domain.ReservationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class StockRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    StockRepository stock;

    @Autowired
    ReservationRepository reservations;

    @Test
    void theDemoStockIsLoadedByTheMigration() {
        assertThat(stock.findById("SKU-1")).get().extracting("available").isEqualTo(100);
    }

    @Test
    void reserveTakesStockOnlyWhileEnoughIsLeft() {
        assertThat(stock.reserve("SKU-2", 3)).isEqualTo(1);
        assertThat(stock.reserve("SKU-2", 3)).isZero();   // only 2 left
        assertThat(stock.reserve("SKU-2", 2)).isEqualTo(1);
        assertThat(stock.reserve("SKU-2", 1)).isZero();   // now empty
    }

    @Test
    void reserveOfAnUnknownProductChangesNothing() {
        assertThat(stock.reserve("NOPE", 1)).isZero();
    }

    @Test
    void releaseAddsStockBack() {
        stock.reserve("SKU-2", 5);
        stock.release("SKU-2", 5);
        stock.flush();

        assertThat(stock.reserve("SKU-2", 5)).isEqualTo(1);
    }

    @Test
    void anOrderHasExactlyOneReservationRow() {
        UUID orderId = UUID.randomUUID();
        reservations.saveAndFlush(Reservation.reserved(orderId, "SKU-1", 1, NOW));

        assertThat(reservations.findById(orderId)).get().extracting("status").isEqualTo(ReservationStatus.RESERVED);
    }
}
