package com.resilient.orders.api;

import com.resilient.orders.domain.PurchaseOrder;
import com.resilient.orders.service.IdempotencyKeyReusedException;
import com.resilient.orders.service.OrderNotFoundException;
import com.resilient.orders.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    private static final String BODY = "{\"customerId\":\"c1\",\"productId\":\"p1\",\"quantity\":2,\"amount\":25.00}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OrderService orderService;

    private PurchaseOrder order() {
        return PurchaseOrder.create("key-1", "c1", "p1", 2, new BigDecimal("25.00"), Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void aNewOrderIsAcceptedWithALocation() throws Exception {
        PurchaseOrder order = order();
        when(orderService.create(eq("key-1"), any())).thenReturn(new OrderService.CreateResult(order, true));

        mvc.perform(post("/orders").header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/orders/" + order.getId()))
                .andExpect(jsonPath("$.sagaStatus").value("AWAITING_INVENTORY"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void repeatingARequestWithTheSameKeyReturnsOk() throws Exception {
        when(orderService.create(eq("key-1"), any())).thenReturn(new OrderService.CreateResult(order(), false));

        mvc.perform(post("/orders").header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
    }

    @Test
    void theIdempotencyKeyIsRequired() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anInvalidBodyIsRejectedWithTheFieldErrors() throws Exception {
        String invalid = "{\"customerId\":\"\",\"productId\":\"p1\",\"quantity\":0,\"amount\":25.00}";

        mvc.perform(post("/orders").header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.customerId").exists())
                .andExpect(jsonPath("$.errors.quantity").exists());
    }

    @Test
    void reusingAKeyForADifferentRequestIsAConflict() throws Exception {
        when(orderService.create(eq("key-1"), any())).thenThrow(new IdempotencyKeyReusedException("key-1"));

        mvc.perform(post("/orders").header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void anUnknownOrderIsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.get(id)).thenThrow(new OrderNotFoundException(id));

        mvc.perform(get("/orders/" + id)).andExpect(status().isNotFound());
    }
}
