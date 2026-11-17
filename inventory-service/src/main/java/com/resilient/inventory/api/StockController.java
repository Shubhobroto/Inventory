package com.resilient.inventory.api;

import com.resilient.inventory.repository.StockRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/stock")
public class StockController {

    public record StockResponse(String productId, int available) {
    }

    private final StockRepository stock;

    public StockController(StockRepository stock) {
        this.stock = stock;
    }

    @GetMapping("/{productId}")
    public ResponseEntity<StockResponse> get(@PathVariable String productId) {
        return stock.findById(productId)
                .map(s -> ResponseEntity.ok(new StockResponse(s.getProductId(), s.getAvailable())))
                .orElse(ResponseEntity.notFound().build());
    }
}
