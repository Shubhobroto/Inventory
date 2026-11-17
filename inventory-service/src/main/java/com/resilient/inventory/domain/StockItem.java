package com.resilient.inventory.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "stock")
public class StockItem {

    @Id
    private String productId;

    private int available;

    public StockItem(String productId, int available) {
        this.productId = productId;
        this.available = available;
    }
}
