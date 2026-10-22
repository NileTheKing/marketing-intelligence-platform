package com.axon.entry_service.reservation;

import lombok.Getter;

@Getter
public class FastValidationException extends RuntimeException {
    private final String type;
    private final String message;

    /**
     * Constructs a FastValidationException with the given validation type and detail message.
     *
     * @param type    an identifier categorizing the validation failure
     * @param message a human-readable description of the validation error
     */
    public FastValidationException(String type, String message) {
        this.type = type;
        this.message = message;
    }
}