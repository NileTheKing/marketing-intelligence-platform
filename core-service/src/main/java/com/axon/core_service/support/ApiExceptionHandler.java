package com.axon.core_service.support;

import com.axon.core_service.exception.CampaignActivityNotFoundException;
import com.axon.core_service.exception.BusinessConflictException;
import com.axon.core_service.exception.InvalidRequestException;
import com.axon.core_service.exception.ResourceNotFoundException;
import com.axon.core_service.support.response.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * Builds an ApiErrorResponse for a missing campaign activity and returns it with the exception's HTTP status.
     *
     * @param ex the thrown CampaignActivityNotFoundException containing the reason, campaignActivityId, and HTTP status
     * @return a ResponseEntity whose body is an ApiErrorResponse with error `"CAMPAIGN_ACTIVITY_NOT_FOUND"`, the exception's reason as the message, and the exception's campaignActivityId; the response status is taken from the exception
     */
    @ExceptionHandler(CampaignActivityNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleCampaignActivityNotFound(CampaignActivityNotFoundException ex) {
        ApiErrorResponse body = ApiErrorResponse.builder()
                .error("CAMPAIGN_ACTIVITY_NOT_FOUND")
                .message(ex.getReason())
                .campaignActivityId(ex.getCampaignActivityId())
                .build();
        return ResponseEntity.status(ex.getStatusCode()).body(body);

    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(BusinessConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessConflict(BusinessConflictException ex) {
        return error(HttpStatus.CONFLICT, "BUSINESS_CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(InvalidRequestException ex) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElseGet(() -> ex.getBindingResult().getGlobalErrors().stream()
                        .findFirst()
                        .map(error -> error.getDefaultMessage())
                        .orElse("request validation failed"));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
        ApiErrorResponse body = ApiErrorResponse.builder()
                .error(code)
                .message(message)
                .build();
        return ResponseEntity.status(status).body(body);
    }
}
