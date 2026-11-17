package com.resilient.payment.handler;

import com.resilient.payment.domain.EntryType;
import com.resilient.payment.domain.LedgerEntry;
import com.resilient.payment.domain.Payment;
import com.resilient.payment.domain.PaymentStatus;
import com.resilient.payment.inbox.InboxGuard;
import com.resilient.payment.messaging.Envelope;
import com.resilient.payment.messaging.MessageCodec;
import com.resilient.payment.messaging.Messages.ChargeCustomer;
import com.resilient.payment.messaging.Messages.PaymentCompleted;
import com.resilient.payment.messaging.Messages.PaymentFailed;
import com.resilient.payment.messaging.Messages.PaymentRefunded;
import com.resilient.payment.messaging.Messages.RefundCustomer;
import com.resilient.payment.messaging.Topics;
import com.resilient.payment.outbox.OutboxWriter;
import com.resilient.payment.repository.LedgerEntryRepository;
import com.resilient.payment.repository.PaymentRepository;
import com.resilient.payment.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentCommandHandlerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("25.00");

    private final WalletRepository wallets = mock(WalletRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final LedgerEntryRepository ledger = mock(LedgerEntryRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final InboxGuard inbox = mock(InboxGuard.class);
    private final MessageCodec codec = new MessageCodec();
    private PaymentCommandHandler handler;

    private final UUID orderUuid = UUID.randomUUID();
    private final String orderId = orderUuid.toString();

    @BeforeEach
    void setUp() {
        when(inbox.firstTime(any())).thenReturn(true);
        when(payments.findById(orderUuid)).thenReturn(Optional.empty());
        handler = new PaymentCommandHandler(wallets, payments, ledger, outbox, inbox, codec, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Envelope message(Object payload) {
        return codec.wrap(orderId, payload, NOW);
    }

    private ChargeCustomer charge(BigDecimal amount) {
        return new ChargeCustomer(orderId, "CUST-1", amount);
    }

    // JSON can drop trailing zeros (25.00 becomes 25), so amounts are compared by value, not by scale.
    private static BigDecimal sameAs(BigDecimal expected) {
        return argThat(actual -> actual != null && actual.compareTo(expected) == 0);
    }

    @Test
    void chargesTheWalletWritesTheLedgerAndRepliesCompleted() {
        when(wallets.debit(eq("CUST-1"), sameAs(AMOUNT))).thenReturn(1);

        handler.handle(message(charge(AMOUNT)));

        ArgumentCaptor<Payment> payment = ArgumentCaptor.forClass(Payment.class);
        verify(payments).save(payment.capture());
        assertThat(payment.getValue().getStatus()).isEqualTo(PaymentStatus.CHARGED);
        ArgumentCaptor<LedgerEntry> entry = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledger).save(entry.capture());
        assertThat(entry.getValue().getEntryType()).isEqualTo(EntryType.DEBIT);
        assertThat(entry.getValue().getAmount()).isEqualByComparingTo(AMOUNT);
        verify(outbox).write(Topics.PAYMENT_EVENTS, orderId, new PaymentCompleted(orderId));
    }

    @Test
    void repliesFailedAndWritesNoLedgerEntryWhenTheBalanceIsTooLow() {
        when(wallets.debit(eq("CUST-1"), sameAs(AMOUNT))).thenReturn(0);

        handler.handle(message(charge(AMOUNT)));

        ArgumentCaptor<Payment> payment = ArgumentCaptor.forClass(Payment.class);
        verify(payments).save(payment.capture());
        assertThat(payment.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(ledger, never()).save(any());
        verify(outbox).write(Topics.PAYMENT_EVENTS, orderId, new PaymentFailed(orderId, "INSUFFICIENT_FUNDS"));
    }

    @Test
    void anAmountOfZeroOrLessIsRefusedWithoutTouchingTheWallet() {
        handler.handle(message(charge(BigDecimal.ZERO)));

        verify(wallets, never()).debit(anyString(), any());
        verify(outbox).write(Topics.PAYMENT_EVENTS, orderId, new PaymentFailed(orderId, "INVALID_AMOUNT"));
    }

    @Test
    void aRepeatedChargeCommandChargesOnlyOnceAndRepeatsTheAnswer() {
        when(payments.findById(orderUuid)).thenReturn(Optional.of(Payment.charged(orderUuid, "CUST-1", AMOUNT, NOW)));

        handler.handle(message(charge(AMOUNT)));

        verify(wallets, never()).debit(anyString(), any());
        verify(ledger, never()).save(any());
        verify(outbox).write(Topics.PAYMENT_EVENTS, orderId, new PaymentCompleted(orderId));
    }

    @Test
    void aChargeThatArrivesAfterItsRefundIsRefused() {
        when(payments.findById(orderUuid)).thenReturn(Optional.of(Payment.cancelledBeforeCharged(orderUuid, NOW)));

        handler.handle(message(charge(AMOUNT)));

        verify(wallets, never()).debit(anyString(), any());
        verify(outbox).write(Topics.PAYMENT_EVENTS, orderId, new PaymentFailed(orderId, "CANCELLED"));
    }

    @Test
    void refundReturnsTheMoneyAndWritesAMatchingCreditEntry() {
        Payment payment = Payment.charged(orderUuid, "CUST-1", AMOUNT, NOW);
        when(payments.findById(orderUuid)).thenReturn(Optional.of(payment));

        handler.handle(message(new RefundCustomer(orderId)));

        verify(wallets).credit(eq("CUST-1"), sameAs(AMOUNT));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        ArgumentCaptor<LedgerEntry> entry = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledger).save(entry.capture());
        assertThat(entry.getValue().getEntryType()).isEqualTo(EntryType.CREDIT);
        verify(outbox).write(Topics.PAYMENT_EVENTS, orderId, new PaymentRefunded(orderId));
    }

    @Test
    void refundingTwiceReturnsTheMoneyOnlyOnce() {
        Payment payment = Payment.charged(orderUuid, "CUST-1", AMOUNT, NOW);
        payment.markRefunded(NOW);
        when(payments.findById(orderUuid)).thenReturn(Optional.of(payment));

        handler.handle(message(new RefundCustomer(orderId)));

        verify(wallets, never()).credit(anyString(), any());
        verify(outbox).write(Topics.PAYMENT_EVENTS, orderId, new PaymentRefunded(orderId));
    }

    @Test
    void aRefundForAnOrderThatWasNeverChargedLeavesAMarkerAndMovesNoMoney() {
        handler.handle(message(new RefundCustomer(orderId)));

        ArgumentCaptor<Payment> payment = ArgumentCaptor.forClass(Payment.class);
        verify(payments).save(payment.capture());
        assertThat(payment.getValue().getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(wallets, never()).credit(anyString(), any());
        verify(outbox).write(Topics.PAYMENT_EVENTS, orderId, new PaymentRefunded(orderId));
    }

    @Test
    void aRedeliveredMessageIsIgnored() {
        when(inbox.firstTime(any())).thenReturn(false);

        handler.handle(message(charge(AMOUNT)));

        verifyNoInteractions(wallets, payments, ledger, outbox);
    }
}
