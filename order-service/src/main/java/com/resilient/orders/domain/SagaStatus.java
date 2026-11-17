package com.resilient.orders.domain;

import java.util.Set;

/**
 * Where an order is in the saga.
 * <pre>
 * AWAITING_INVENTORY --reserved--> AWAITING_PAYMENT --paid--> COMPLETED
 *        |                               |
 *     rejected                      payment failed
 *        |                               v
 *        v                         COMPENSATING --stock released--> FAILED
 *      FAILED
 * Any non-final state that stays idle too long goes to FAILED (timeout) with compensating commands sent.
 * </pre>
 */
public enum SagaStatus {
    AWAITING_INVENTORY,
    AWAITING_PAYMENT,
    COMPENSATING,
    COMPLETED,
    FAILED;

    public static final Set<SagaStatus> IN_PROGRESS = Set.of(AWAITING_INVENTORY, AWAITING_PAYMENT, COMPENSATING);
}
