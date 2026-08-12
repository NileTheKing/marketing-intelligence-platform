package com.axon.core_service.config.auth;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@TestComponent
@RestController
class SecurityProbeController {

    @GetMapping("/api/v1/events/active")
    String activeEvents() {
        return "ok";
    }

    @PostMapping("/api/v1/events")
    String createEvent() {
        return "ok";
    }

    @PostMapping("/api/v1/files/upload")
    String uploadFile() {
        return "ok";
    }

    @GetMapping("/api/v1/dashboard/overview")
    String dashboard() {
        return "ok";
    }

    @GetMapping("/api/v1/validation")
    String validation() {
        return "ok";
    }

    @GetMapping("/api/v1/campaign-activities/{campaignActivityId}")
    String activityMetadata() {
        return "ok";
    }

    @GetMapping("/api/v1/campaign-activities/{campaignActivityId}/entries")
    String activityEntries() {
        return "ok";
    }

    @GetMapping("/api/v1/campaign-activities/count")
    String activityCount() {
        return "ok";
    }

    @GetMapping("/core/api/v1/reconciliation-issues")
    String reconciliationIssues() {
        return "ok";
    }

    @GetMapping("/admin")
    String admin() {
        return "ok";
    }

    @GetMapping("/products/1")
    String productPage() {
        return "ok";
    }
}
