package com.resilient.orders.service;

import com.resilient.orders.api.CreateOrderRequest;
import com.resilient.orders.domain.PurchaseOrder;
import com.resilient.orders.messaging.Messages.ReserveInventory;
import com.resilient.orders.messaging.Topics;
import com.resilient.orders.outbox.OutboxWriter;
import com.resilient.orders.repository.PurchaseOrderRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrderService {

    /** The order and whether this call created it (false means an earlier call with the same key did). */
    public record CreateResult(PurchaseOrder order, boolean created) {
    }

    private final PurchaseOrderRepository orders;
    private final OutboxWriter outbox;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public OrderService(PurchaseOrderRepository orders, OutboxWriter outbox, TransactionTemplate transaction, Clock clock) {
        this.orders = orders;
        this.outbox = outbox;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * Idempotent create. The client sends an Idempotency-Key; repeating the call with the same key and body
     * returns the same order instead of creating a second one, so a retry after a timeout is safe.
     * The unique constraint on the key settles two calls that arrive at the same instant.
     */
    public CreateResult create(String idempotencyKey, CreateOrderRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new IllegalArgumentException("Idempotency-Key must be 1 to 100 characters");
        }
        Optional<PurchaseOrder> existing = orders.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), idempotencyKey, request);
        }
        try {
            PurchaseOrder created = transaction.execute(status -> insert(idempotencyKey, request));
            return new CreateResult(created, true);
        } catch (DataIntegrityViolationException race) {
            PurchaseOrder winner = orders.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> race);
            return replay(winner, idempotencyKey, request);
        }
    }

    public PurchaseOrder get(UUID id) {
        return orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    // The order row and its first command are written together; the outbox publisher sends the command later.
    private PurchaseOrder insert(String idempotencyKey, CreateOrderRequest request) {
        PurchaseOrder order = orders.save(PurchaseOrder.create(idempotencyKey, request.customerId(),
                request.productId(), request.quantity(), request.amount(), clock.instant()));
        outbox.write(Topics.INVENTORY_COMMANDS, order.getId().toString(),
                new ReserveInventory(order.getId().toString(), order.getProductId(), order.getQuantity()));
        return order;
    }

    private CreateResult replay(PurchaseOrder existing, String key, CreateOrderRequest request) {
        boolean same = existing.getCustomerId().equals(request.customerId())
                && existing.getProductId().equals(request.productId())
                && existing.getQuantity() == request.quantity()
                && existing.getAmount().compareTo(request.amount()) == 0;
        if (!same) {
            throw new IdempotencyKeyReusedException(key);
        }
        return new CreateResult(existing, false);
    }
}
