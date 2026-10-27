package com.axon.entry_service.reservation;

import com.axon.entry_service.reservation.dto.EntryRequestDto;
import com.axon.entry_service.payment.dto.PaymentConfirmationResponse;
import com.axon.entry_service.reservation.domain.ReservationResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
public class EntryController {
    private final EntryApplicationService entryApplicationService;

    @ExceptionHandler(CoreValidationUnavailableException.class)
    public ResponseEntity<PaymentConfirmationResponse> validationUnavailable(CoreValidationUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(PaymentConfirmationResponse.failure(ReservationResult.error(), exception.getMessage()));
    }

    @PostMapping("/api/v1/entries")
    public ResponseEntity<?> createEntry(@Valid @RequestBody EntryRequestDto requestDto,
            @RequestHeader("Authorization") String token,
            @AuthenticationPrincipal UserDetails userDetails) {
        log.debug("요청 확인 {}", requestDto);
        long userId = Long.parseLong(userDetails.getUsername());
        return toResponse(entryApplicationService.createEntry(requestDto, token, userId));
    }

    private ResponseEntity<?> toResponse(EntryUseCaseResult result) {
        HttpStatus status = switch (result.status()) {
            case OK -> HttpStatus.OK;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case CONFLICT -> HttpStatus.CONFLICT;
            case GONE -> HttpStatus.GONE;
            case INTERNAL_SERVER_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        if (result.body() == null) {
            return ResponseEntity.status(status).build();
        }
        return ResponseEntity.status(status).body(result.body());
    }
}
