package com.resilient.payment.api;

import com.resilient.payment.domain.LedgerEntry;
import com.resilient.payment.repository.LedgerEntryRepository;
import com.resilient.payment.repository.WalletRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class WalletController {

    public record WalletResponse(String customerId, BigDecimal balance) {
    }

    public record LedgerResponse(String type, String customerId, BigDecimal amount, Instant createdAt) {
        static LedgerResponse from(LedgerEntry e) {
            return new LedgerResponse(e.getEntryType().name(), e.getCustomerId(), e.getAmount(), e.getCreatedAt());
        }
    }

    private final WalletRepository wallets;
    private final LedgerEntryRepository ledger;

    public WalletController(WalletRepository wallets, LedgerEntryRepository ledger) {
        this.wallets = wallets;
        this.ledger = ledger;
    }

    @GetMapping("/wallets/{customerId}")
    public ResponseEntity<WalletResponse> wallet(@PathVariable String customerId) {
        return wallets.findById(customerId)
                .map(w -> ResponseEntity.ok(new WalletResponse(w.getCustomerId(), w.getBalance())))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/ledger/{orderId}")
    public List<LedgerResponse> ledger(@PathVariable UUID orderId) {
        return ledger.findByOrderIdOrderById(orderId).stream().map(LedgerResponse::from).toList();
    }
}
