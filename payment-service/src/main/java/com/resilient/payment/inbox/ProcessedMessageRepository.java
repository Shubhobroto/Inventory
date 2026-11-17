package com.resilient.payment.inbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, UUID> {

    @Modifying
    @Query("DELETE FROM ProcessedMessage m WHERE m.processedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
