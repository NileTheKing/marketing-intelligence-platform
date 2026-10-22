package com.axon.entry_service.service.exception;

public class BehaviorEventValidationException extends RuntimeException {

    public BehaviorEventValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
