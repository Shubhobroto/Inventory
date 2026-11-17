package com.resilient.payment.repository;

import com.resilient.payment.domain.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface WalletRepository extends JpaRepository<Wallet, String> {

    /** Compare-and-set: debits only if the balance covers the amount, so a wallet can never go negative. */
    @Modifying
    @Query("UPDATE Wallet w SET w.balance = w.balance - :amount WHERE w.customerId = :customerId AND w.balance >= :amount")
    int debit(@Param("customerId") String customerId, @Param("amount") BigDecimal amount);

    @Modifying
    @Query("UPDATE Wallet w SET w.balance = w.balance + :amount WHERE w.customerId = :customerId")
    int credit(@Param("customerId") String customerId, @Param("amount") BigDecimal amount);
}
