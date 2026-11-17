package com.resilient.payment.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only record of every money movement. Rows are never updated or deleted; the balance can always
 * be checked against the sum of the entries. A unique (order, type) pair means one debit and one credit
 * per order at most.
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID orderId;
    private String customerId;

    @Enumerated(EnumType.STRING)
    private EntryType entryType;

    private BigDecimal amount;
    private Instant createdAt;

    public LedgerEntry(UUID orderId, String customerId, EntryType entryType, BigDecimal amount, Instant createdAt) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.entryType = entryType;
        this.amount = amount;
        this.createdAt = createdAt;
    }
}
