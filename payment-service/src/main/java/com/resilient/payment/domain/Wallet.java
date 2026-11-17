package com.resilient.payment.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    private String customerId;

    private BigDecimal balance;

    public Wallet(String customerId, BigDecimal balance) {
        this.customerId = customerId;
        this.balance = balance;
    }
}
