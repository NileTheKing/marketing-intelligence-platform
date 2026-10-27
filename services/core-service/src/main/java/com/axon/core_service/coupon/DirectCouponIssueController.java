package com.axon.core_service.coupon;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class DirectCouponIssueController {
    private final DirectCouponIssueService service;

    @PostMapping("/api/v1/coupon-activities/{activityId}/issuances")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> issue(@PathVariable long activityId, Authentication principal) {
        return Map.of("userCouponId", service.issue(activityId, Long.parseLong(principal.getName())));
    }
}
