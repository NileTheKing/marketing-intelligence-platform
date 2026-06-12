package com.axon.entry_service.controller;

import com.axon.entry_service.dto.EntryRequestDto;
import com.axon.entry_service.service.entry.EntryApplicationService;
import com.axon.entry_service.service.entry.EntryUseCaseResult;
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
@RequestMapping("/entry/api/v1/entries")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // Same-origin via Ingress, wildcard for flexibility
public class EntryController {
    private final EntryApplicationService entryApplicationService;

    @PostMapping("/coupon")
    public ResponseEntity<?> issueCoupon(@Valid @RequestBody EntryRequestDto requestDto,
            @RequestHeader("Authorization") String token,
            @AuthenticationPrincipal UserDetails userDetails) {
        long userId = Long.parseLong(userDetails.getUsername());
        return toResponse(entryApplicationService.issueCoupon(requestDto, token, userId));
    }

    /**
     * Processes an entry creation request: validates eligibility, attempts an
     * atomic reservation, and emits a campaign activity event.
     *
     * @param requestDto  the entry request containing campaignActivityId,
     *                    productId, and optional activityType
     * @param token       the raw "Authorization" header value used for heavy
     *                    eligibility validation
     * @param userDetails the authenticated principal whose username is parsed as
     *                    the numeric userId
     * @return a ResponseEntity with status:
     *         202 Accepted on successful reservation and event emission;
     *         404 Not Found if campaign metadata is missing;
     *         400 Bad Request for fast- or heavy-validation failures or when the
     *         activity is closed;
     *         409 Conflict when the entry is duplicated;
     *         410 Gone when the activity is sold out;
     *         500 Internal Server Error for unexpected reservation failures.
     */

    @PostMapping
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
