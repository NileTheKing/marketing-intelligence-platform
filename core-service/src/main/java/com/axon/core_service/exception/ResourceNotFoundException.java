package com.axon.core_service.exception;

public class ResourceNotFoundException extends IllegalArgumentException {

    public ResourceNotFoundException(String resourceName, Object resourceId) {
        super("%s not found: %s".formatted(resourceName, resourceId));
    }
}
