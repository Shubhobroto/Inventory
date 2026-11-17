package com.resilient.payment.handler;

import com.resilient.payment.domain.LedgerEntry;
import com.resilient.payment.messaging.Envelope;
import com.resilient.payment.messaging.MessageCodec;
import com.resilient.payment.messaging.Messages.ChargeCustomer;
import com.resilient.payment.messaging.Messages.RefundCustomer;
import com.resilient.payment.outbox.OutboxEvent;
import com.resilient.payment.outbox.OutboxRepository;
import com.resilient.payment.repository.LedgerEntryRepository;
import com.resilient.payment.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Spring wiring, transactions and database (H2), no Kafka: commands are handed to the handler directly. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payment-flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "outbox.poll-interval-ms=3600000",
        "outbox.cleanup-interval-ms=3600000"})
class PaymentFlowTest {

    @Autowired
    PaymentCommandHandler handler;
    @Autowired
    MessageCodec codec;
    @Autowired
    WalletRepository wallets;
    @Autowired
    LedgerEntryRepository ledger;
    @Autowired
    OutboxRepository outbox;

    private BigDecimal balance(String customerId) {
        return wallets.findById(customerId).orElseThrow().getBalance();
    }

    private Envelope command(String orderId, Object payload) {
        return codec.wrap(orderId, payload, Instant.now());
    }

    private List<String> replies(String orderId) {
        return outbox.findAll(Sort.by("id")).stream()
                .filter(e -> e.getMessageKey().equals(orderId))
                .map(OutboxEvent::getEventType)
                .toList();
    }

    @Test
    void aChargeDebitsTheWalletOnceAndWritesOneLedgerEntryEvenIfRepeated() {
        String orderId = UUID.randomUUID().toString();
        BigDecimal before = balance("CUST-1");
        BigDecimal amount = new BigDecimal("25.00");

        handler.handle(command(orderId, new ChargeCustomer(orderId, "CUST-1", amount)));
        // a duplicate command with a new message id: the order id is the natural key
        handler.handle(command(orderId, new ChargeCustomer(orderId, "CUST-1", amount)));

        assertThat(balance("CUST-1")).isEqualByComparingTo(before.subtract(amount));
        assertThat(ledger.findByOrderIdOrderById(UUID.fromString(orderId))).hasSize(1);
        assertThat(replies(orderId)).containsExactly("PaymentCompleted", "PaymentCompleted");
    }

    @Test
    void theSameMessageDeliveredTwiceIsProcessedOnce() {
        String orderId = UUID.randomUUID().toString();
        BigDecimal before = balance("CUST-1");
        Envelope charge = command(orderId, new ChargeCustomer(orderId, "CUST-1", new BigDecimal("10.00")));

        handler.handle(charge);
        handler.handle(charge);

        assertThat(balance("CUST-1")).isEqualByComparingTo(before.subtract(new BigDecimal("10.00")));
        assertThat(replies(orderId)).containsExactly("PaymentCompleted");
    }

    @Test
    void aChargeBeyondTheBalanceFailsAndLeavesTheWalletAndLedgerUntouched() {
        String orderId = UUID.randomUUID().toString();
        BigDecimal before = balance("CUST-2");

        handler.handle(command(orderId, new ChargeCustomer(orderId, "CUST-2", new BigDecimal("500.00"))));

        assertThat(balance("CUST-2")).isEqualByComparingTo(before);
        assertThat(ledger.findByOrderIdOrderById(UUID.fromString(orderId))).isEmpty();
        assertThat(replies(orderId)).containsExactly("PaymentFailed");
    }

    @Test
    void refundReturnsTheMoneyOnceAndTheLedgerBalances() {
        String orderId = UUID.randomUUID().toString();
        BigDecimal before = balance("CUST-1");
        BigDecimal amount = new BigDecimal("40.00");
        handler.handle(command(orderId, new ChargeCustomer(orderId, "CUST-1", amount)));

        handler.handle(command(orderId, new RefundCustomer(orderId)));
        handler.handle(command(orderId, new RefundCustomer(orderId)));

        assertThat(balance("CUST-1")).isEqualByComparingTo(before);
        List<LedgerEntry> entries = ledger.findByOrderIdOrderById(UUID.fromString(orderId));
        assertThat(entries).extracting(e -> e.getEntryType().name()).containsExactly("DEBIT", "CREDIT");
    }

    @Test
    void aChargeThatArrivesAfterTheRefundIsRefusedAndMovesNoMoney() {
        String orderId = UUID.randomUUID().toString();
        BigDecimal before = balance("CUST-1");

        handler.handle(command(orderId, new RefundCustomer(orderId)));
        handler.handle(command(orderId, new ChargeCustomer(orderId, "CUST-1", new BigDecimal("30.00"))));

        assertThat(balance("CUST-1")).isEqualByComparingTo(before);
        assertThat(replies(orderId)).containsExactly("PaymentRefunded", "PaymentFailed");
    }
}
