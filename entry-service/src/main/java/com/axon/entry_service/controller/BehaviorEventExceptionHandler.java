package com.axon.entry_service.controller;

import com.axon.entry_service.service.exception.BehaviorEventValidationException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = BehaviorEventController.class)
public class BehaviorEventExceptionHandler {

    @ExceptionHandler(BehaviorEventValidationException.class)
    public ResponseEntity<Map<String, String>> handleBehaviorEventValidation(BehaviorEventValidationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", exception.getMessage()));
    }
}
