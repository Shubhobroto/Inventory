package com.resilient.orders.api;

import com.resilient.orders.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Accepts the order and returns at once; the saga finishes in the background.
     * 202 Accepted for a new order, 200 when the same Idempotency-Key was already used.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                @Valid @RequestBody CreateOrderRequest request) {
        OrderService.CreateResult result = orderService.create(idempotencyKey, request);
        OrderResponse body = OrderResponse.from(result.order());
        URI location = URI.create("/orders/" + body.id());
        return result.created()
                ? ResponseEntity.status(HttpStatus.ACCEPTED).location(location).body(body)
                : ResponseEntity.ok().location(location).body(body);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable UUID id) {
        return OrderResponse.from(orderService.get(id));
    }
}
