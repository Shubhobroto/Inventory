package com.resilient.inventory.repository;

import com.resilient.inventory.domain.StockItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockRepository extends JpaRepository<StockItem, String> {

    /** Compare-and-set: takes stock only if enough is left, so stock can never go negative. Returns 0 if not. */
    @Modifying
    @Query("UPDATE StockItem s SET s.available = s.available - :quantity WHERE s.productId = :productId AND s.available >= :quantity")
    int reserve(@Param("productId") String productId, @Param("quantity") int quantity);

    @Modifying
    @Query("UPDATE StockItem s SET s.available = s.available + :quantity WHERE s.productId = :productId")
    int release(@Param("productId") String productId, @Param("quantity") int quantity);
}
