package com.resilient.orders.saga;

import com.resilient.orders.config.SagaProperties;
import com.resilient.orders.domain.OrderStatus;
import com.resilient.orders.domain.PurchaseOrder;
import com.resilient.orders.domain.SagaStatus;
import com.resilient.orders.inbox.InboxGuard;
import com.resilient.orders.messaging.Envelope;
import com.resilient.orders.messaging.MessageCodec;
import com.resilient.orders.messaging.Messages.ChargeCustomer;
import com.resilient.orders.messaging.Messages.InventoryRejected;
import com.resilient.orders.messaging.Messages.InventoryReleased;
import com.resilient.orders.messaging.Messages.InventoryReserved;
import com.resilient.orders.messaging.Messages.PaymentCompleted;
import com.resilient.orders.messaging.Messages.PaymentFailed;
import com.resilient.orders.messaging.Messages.RefundCustomer;
import com.resilient.orders.messaging.Messages.ReleaseInventory;
import com.resilient.orders.messaging.Topics;
import com.resilient.orders.outbox.OutboxWriter;
import com.resilient.orders.repository.PurchaseOrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Orchestration-based saga. This service decides the next step after every reply:
 * reserve stock, then charge, and compensate (release stock) if the charge fails.
 *
 * Every handler first moves the order with a compare-and-set on its saga state. A duplicate, late or
 * racing message finds the state already changed, moves nothing and sends nothing.
 * The state change and the next command are written in one transaction (outbox).
 */
@Slf4j
@Service
public class SagaOrchestrator {

    private final PurchaseOrderRepository orders;
    private final OutboxWriter outbox;
    private final InboxGuard inbox;
    private final MessageCodec codec;
    private final SagaProperties props;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final Timer sagaDuration;

    public SagaOrchestrator(PurchaseOrderRepository orders, OutboxWriter outbox, InboxGuard inbox, MessageCodec codec,
                            SagaProperties props, Clock clock, MeterRegistry metrics) {
        this.orders = orders;
        this.outbox = outbox;
        this.inbox = inbox;
        this.codec = codec;
        this.props = props;
        this.clock = clock;
        this.metrics = metrics;
        this.sagaDuration = Timer.builder("saga.duration")
                .description("Time from order creation to a final state")
                .publishPercentileHistogram()
                .register(metrics);
    }

    @Transactional
    public void handle(Envelope envelope) {
        if (!inbox.firstTime(envelope.messageId())) {
            log.info("Duplicate message ignored type={} messageId={}", envelope.type(), envelope.messageId());
            return;
        }
        switch (envelope.type()) {
            case "InventoryReserved" -> onInventoryReserved(codec.payload(envelope, InventoryReserved.class));
            case "InventoryRejected" -> onInventoryRejected(codec.payload(envelope, InventoryRejected.class));
            case "InventoryReleased" -> onInventoryReleased(codec.payload(envelope, InventoryReleased.class));
            case "PaymentCompleted" -> onPaymentCompleted(codec.payload(envelope, PaymentCompleted.class));
            case "PaymentFailed" -> onPaymentFailed(codec.payload(envelope, PaymentFailed.class));
            default -> log.debug("No saga step for message type {}", envelope.type());
        }
    }

    private void onInventoryReserved(InventoryReserved event) {
        UUID id = UUID.fromString(event.orderId());
        if (!move(id, SagaStatus.AWAITING_INVENTORY, SagaStatus.AWAITING_PAYMENT, OrderStatus.PENDING, null)) {
            return;
        }
        PurchaseOrder order = orders.findById(id).orElseThrow();
        outbox.write(Topics.PAYMENT_COMMANDS, event.orderId(),
                new ChargeCustomer(event.orderId(), order.getCustomerId(), order.getAmount()));
    }

    private void onInventoryRejected(InventoryRejected event) {
        UUID id = UUID.fromString(event.orderId());
        if (move(id, SagaStatus.AWAITING_INVENTORY, SagaStatus.FAILED, OrderStatus.REJECTED, event.reason())) {
            finished("rejected", id);
        }
    }

    private void onPaymentCompleted(PaymentCompleted event) {
        UUID id = UUID.fromString(event.orderId());
        if (move(id, SagaStatus.AWAITING_PAYMENT, SagaStatus.COMPLETED, OrderStatus.CONFIRMED, null)) {
            finished("completed", id);
        }
    }

    private void onPaymentFailed(PaymentFailed event) {
        UUID id = UUID.fromString(event.orderId());
        if (move(id, SagaStatus.AWAITING_PAYMENT, SagaStatus.COMPENSATING, OrderStatus.PENDING, event.reason())) {
            // Compensation: give the reserved stock back.
            outbox.write(Topics.INVENTORY_COMMANDS, event.orderId(), new ReleaseInventory(event.orderId()));
        }
    }

    private void onInventoryReleased(InventoryReleased event) {
        UUID id = UUID.fromString(event.orderId());
        String reason = orders.findById(id).map(PurchaseOrder::getFailureReason).orElse(null);
        if (move(id, SagaStatus.COMPENSATING, SagaStatus.FAILED, OrderStatus.CANCELLED, reason)) {
            finished("compensated", id);
        }
    }

    /**
     * Cancels orders that have been waiting too long and sends both compensations. They are safe to send
     * even if the step never happened: the other services record a "cancelled" marker so a late command is refused.
     */
    @Transactional
    public int failStuckOrders() {
        Instant cutoff = clock.instant().minus(props.timeout());
        List<PurchaseOrder> stuck = orders.findStuck(SagaStatus.IN_PROGRESS, cutoff, PageRequest.of(0, props.timeoutBatchSize()));
        int failed = 0;
        for (PurchaseOrder order : stuck) {
            if (move(order.getId(), order.getSagaStatus(), SagaStatus.FAILED, OrderStatus.CANCELLED, "TIMEOUT")) {
                String id = order.getId().toString();
                outbox.write(Topics.INVENTORY_COMMANDS, id, new ReleaseInventory(id));
                outbox.write(Topics.PAYMENT_COMMANDS, id, new RefundCustomer(id));
                finished("timed_out", order.getId());
                failed++;
            }
        }
        return failed;
    }

    private boolean move(UUID id, SagaStatus from, SagaStatus to, OrderStatus orderStatus, String reason) {
        boolean moved = orders.transition(id, from, to, orderStatus, reason, clock.instant()) == 1;
        if (!moved) {
            log.info("Ignored, order is no longer {} orderId={} (duplicate or late message)", from, id);
        }
        return moved;
    }

    private void finished(String outcome, UUID id) {
        metrics.counter("saga.outcome", "result", outcome).increment();
        orders.findById(id).ifPresent(o -> sagaDuration.record(Duration.between(o.getCreatedAt(), clock.instant())));
        log.info("Saga finished result={} orderId={}", outcome, id);
    }
}
