package com.resilient.orders.saga;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SagaTimeoutJob {

    private final SagaOrchestrator orchestrator;

    public SagaTimeoutJob(SagaOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    // Runs on every instance; the compare-and-set on the saga state lets only one of them cancel a given order.
    @Scheduled(fixedDelayString = "${saga.timeout-check-interval-ms:10000}")
    public void run() {
        int failed = orchestrator.failStuckOrders();
        if (failed > 0) {
            log.warn("Cancelled {} orders that timed out", failed);
        }
    }
}
