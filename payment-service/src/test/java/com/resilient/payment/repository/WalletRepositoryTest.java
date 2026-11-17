package com.resilient.payment.repository;

import com.resilient.payment.domain.EntryType;
import com.resilient.payment.domain.LedgerEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WalletRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    WalletRepository wallets;

    @Autowired
    LedgerEntryRepository ledger;

    @Test
    void theDemoWalletsAreLoadedByTheMigration() {
        assertThat(wallets.findById("CUST-1")).get().extracting("balance").isEqualTo(new BigDecimal("1000.00"));
    }

    @Test
    void debitSucceedsOnlyWhileTheBalanceCoversTheAmount() {
        assertThat(wallets.debit("CUST-2", new BigDecimal("6.00"))).isEqualTo(1);
        assertThat(wallets.debit("CUST-2", new BigDecimal("6.00"))).isZero();   // only 4.00 left
        assertThat(wallets.debit("CUST-2", new BigDecimal("4.00"))).isEqualTo(1);
        assertThat(wallets.debit("CUST-2", new BigDecimal("0.01"))).isZero();   // empty
    }

    @Test
    void debitOfAnUnknownCustomerChangesNothing() {
        assertThat(wallets.debit("NOBODY", new BigDecimal("1.00"))).isZero();
    }

    @Test
    void creditAddsMoneyBack() {
        wallets.debit("CUST-2", new BigDecimal("10.00"));
        wallets.credit("CUST-2", new BigDecimal("10.00"));

        assertThat(wallets.debit("CUST-2", new BigDecimal("10.00"))).isEqualTo(1);
    }

    @Test
    void theLedgerAllowsOneEntryOfEachTypePerOrder() {
        UUID orderId = UUID.randomUUID();
        ledger.saveAndFlush(new LedgerEntry(orderId, "CUST-1", EntryType.DEBIT, new BigDecimal("5.00"), NOW));
        ledger.saveAndFlush(new LedgerEntry(orderId, "CUST-1", EntryType.CREDIT, new BigDecimal("5.00"), NOW));

        assertThrows(DataIntegrityViolationException.class, () ->
                ledger.saveAndFlush(new LedgerEntry(orderId, "CUST-1", EntryType.DEBIT, new BigDecimal("5.00"), NOW)));
    }

    @Test
    void ledgerEntriesComeBackInTheOrderTheyWereWritten() {
        UUID orderId = UUID.randomUUID();
        ledger.saveAndFlush(new LedgerEntry(orderId, "CUST-1", EntryType.DEBIT, new BigDecimal("5.00"), NOW));
        ledger.saveAndFlush(new LedgerEntry(orderId, "CUST-1", EntryType.CREDIT, new BigDecimal("5.00"), NOW));

        List<LedgerEntry> entries = ledger.findByOrderIdOrderById(orderId);

        assertThat(entries).extracting(LedgerEntry::getEntryType).containsExactly(EntryType.DEBIT, EntryType.CREDIT);
    }
}
