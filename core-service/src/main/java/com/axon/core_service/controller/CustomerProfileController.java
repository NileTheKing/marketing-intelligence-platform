package com.axon.core_service.controller;

import com.axon.core_service.domain.dto.customer.CustomerProfileResponse;
import com.axon.core_service.service.CustomerProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard/customers")
@RequiredArgsConstructor
public class CustomerProfileController {

    private final CustomerProfileService customerProfileService;

    @GetMapping("/{userId}")
    public ResponseEntity<CustomerProfileResponse> getProfile(@PathVariable Long userId) {
        return ResponseEntity.ok(customerProfileService.getProfile(userId));
    }
}
