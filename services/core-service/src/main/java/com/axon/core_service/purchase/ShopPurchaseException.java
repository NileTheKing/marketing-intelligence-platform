package com.axon.core_service.purchase;

import org.springframework.http.HttpStatus;

public class ShopPurchaseException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ShopPurchaseException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
}
