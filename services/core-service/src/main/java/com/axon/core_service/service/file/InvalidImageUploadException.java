package com.axon.core_service.service.file;

public class InvalidImageUploadException extends RuntimeException {

    public InvalidImageUploadException(String message) {
        super(message);
    }

    public InvalidImageUploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
