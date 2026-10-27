package com.axon.core_service.purchase;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/shop-purchases")
@RequiredArgsConstructor
public class ShopPurchaseController {
    private final ShopPurchaseApplicationService purchases;

    @PostMapping
    public ResponseEntity<ShopPurchaseResponse> confirm(Authentication principal,
            @Valid @RequestBody ShopPurchaseRequest request) {
        var result = purchases.confirm(Long.valueOf(principal.getName()), request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .location(URI.create("/api/v1/shop-purchases/" + result.purchase().purchaseId()))
                .body(result.purchase());
    }

    @GetMapping("/{purchaseId}")
    public ShopPurchaseResponse get(Authentication principal, @PathVariable Long purchaseId) {
        return purchases.getPurchase(Long.valueOf(principal.getName()), purchaseId);
    }

    @ExceptionHandler(ShopPurchaseException.class)
    public ResponseEntity<Map<String, String>> handle(ShopPurchaseException failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("code", failure.code()));
    }

    @ExceptionHandler({TransientDataAccessException.class, RecoverableDataAccessException.class,
            DataAccessResourceFailureException.class})
    public ResponseEntity<Map<String, String>> unavailable(Exception failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code", "PURCHASE_UNAVAILABLE"));
    }
}
