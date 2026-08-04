package com.axon.core_service.commandprocessing;

public class OffsetCommitBlockedException extends RuntimeException {

    public OffsetCommitBlockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
