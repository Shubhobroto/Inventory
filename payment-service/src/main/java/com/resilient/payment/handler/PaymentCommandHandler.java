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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles charge and refund commands against the customer wallet. Each message is one database transaction:
 * wallet change, ledger entry, payment row, inbox entry and the reply (outbox) commit together or not at all.
 *
 * Like inventory, it is safe against repeats and reordering: one payment row per order remembers the outcome,
 * and a refund that arrives before its charge leaves a marker so the late charge is refused.
 */
@Slf4j
@Service
public class PaymentCommandHandler {

    static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    static final String INVALID_AMOUNT = "INVALID_AMOUNT";
    static final String CANCELLED = "CANCELLED";

    private final WalletRepository wallets;
    private final PaymentRepository payments;
    private final LedgerEntryRepository ledger;
    private final OutboxWriter outbox;
    private final InboxGuard inbox;
    private final MessageCodec codec;
    private final Clock clock;

    public PaymentCommandHandler(WalletRepository wallets, PaymentRepository payments, LedgerEntryRepository ledger,
                                 OutboxWriter outbox, InboxGuard inbox, MessageCodec codec, Clock clock) {
        this.wallets = wallets;
        this.payments = payments;
        this.ledger = ledger;
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
            case "ChargeCustomer" -> charge(codec.payload(envelope, ChargeCustomer.class));
            case "RefundCustomer" -> refund(codec.payload(envelope, RefundCustomer.class));
            default -> log.debug("Ignoring message type {}", envelope.type());
        }
    }

    private void charge(ChargeCustomer command) {
        UUID orderId = UUID.fromString(command.orderId());
        Optional<Payment> existing = payments.findById(orderId);
        if (existing.isPresent()) {
            repeatAnswer(existing.get());
            return;
        }
        Instant now = clock.instant();
        BigDecimal amount = command.amount();
        if (amount == null || amount.signum() <= 0) {
            payments.save(Payment.failed(orderId, command.customerId(), BigDecimal.ZERO, now));
            reply(command.orderId(), new PaymentFailed(command.orderId(), INVALID_AMOUNT));
        } else if (wallets.debit(command.customerId(), amount) == 1) {
            payments.save(Payment.charged(orderId, command.customerId(), amount, now));
            ledger.save(new LedgerEntry(orderId, command.customerId(), EntryType.DEBIT, amount, now));
            reply(command.orderId(), new PaymentCompleted(command.orderId()));
        } else {
            payments.save(Payment.failed(orderId, command.customerId(), amount, now));
            reply(command.orderId(), new PaymentFailed(command.orderId(), INSUFFICIENT_FUNDS));
        }
    }

    private void refund(RefundCustomer command) {
        UUID orderId = UUID.fromString(command.orderId());
        Optional<Payment> existing = payments.findById(orderId);
        Instant now = clock.instant();
        if (existing.isEmpty()) {
            payments.save(Payment.cancelledBeforeCharged(orderId, now));
        } else if (existing.get().getStatus() == PaymentStatus.CHARGED) {
            Payment payment = existing.get();
            wallets.credit(payment.getCustomerId(), payment.getAmount());
            ledger.save(new LedgerEntry(orderId, payment.getCustomerId(), EntryType.CREDIT, payment.getAmount(), now));
            payment.markRefunded(now);
        }
        // FAILED, CANCELLED or already REFUNDED: nothing to give back.
        reply(command.orderId(), new PaymentRefunded(command.orderId()));
    }

    private void repeatAnswer(Payment payment) {
        String orderId = payment.getOrderId().toString();
        switch (payment.getStatus()) {
            case CHARGED -> reply(orderId, new PaymentCompleted(orderId));
            case FAILED -> reply(orderId, new PaymentFailed(orderId, INSUFFICIENT_FUNDS));
            case REFUNDED, CANCELLED -> reply(orderId, new PaymentFailed(orderId, CANCELLED));
        }
    }

    private void reply(String orderId, Object event) {
        outbox.write(Topics.PAYMENT_EVENTS, orderId, event);
    }
}
