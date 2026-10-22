package com.axon.core_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axon.core_service.AbstractIntegrationTest;
import com.axon.core_service.domain.campaign.Campaign;
import com.axon.core_service.domain.campaignactivity.CampaignActivityRun;
import com.axon.core_service.domain.campaignactivity.CampaignActivityRunTarget;
import com.axon.core_service.domain.campaignactivity.CampaignActivityRunTargetGroup;
import com.axon.core_service.domain.campaignactivity.CampaignActivityPurpose;
import com.axon.core_service.coupon.domain.Coupon;
import com.axon.core_service.coupon.domain.UserCoupon;
import com.axon.core_service.domain.marketing.AudienceSegment;
import com.axon.core_service.domain.marketing.MarketingActionDispatch;
import com.axon.core_service.domain.user.Role;
import com.axon.core_service.domain.user.RfmSegment;
import com.axon.core_service.domain.user.User;
import com.axon.core_service.domain.user.UserSummary;
import com.axon.core_service.repository.AudienceSegmentRepository;
import com.axon.core_service.repository.CampaignActivityRunRepository;
import com.axon.core_service.repository.CampaignActivityRunTargetRepository;
import com.axon.core_service.repository.CampaignRepository;
import com.axon.core_service.coupon.CouponRepository;
import com.axon.core_service.repository.MarketingActionDispatchRepository;
import com.axon.core_service.repository.MarketingActionExecutionRepository;
import com.axon.core_service.coupon.UserCouponRepository;
import com.axon.core_service.repository.UserRepository;
import com.axon.core_service.repository.UserSummaryRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.TestPropertySource;

@AutoConfigureMockMvc
@TestPropertySource(properties = "axon.triage.service-token=phase-one-test-service-token")
class CampaignRunWorkflowIntegrationTest extends AbstractIntegrationTest {

    private static final String INTERNAL_TOKEN = "phase-one-test-service-token";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CampaignRepository campaignRepository;
    @Autowired CampaignActivityRunRepository runRepository;
    @Autowired CampaignActivityRunTargetRepository targetRepository;
    @Autowired AudienceSegmentRepository segmentRepository;
    @Autowired CouponRepository couponRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserSummaryRepository userSummaryRepository;
    @Autowired UserCouponRepository userCouponRepository;
    @Autowired MarketingActionExecutionRepository executionRepository;
    @Autowired MarketingActionDispatchRepository dispatchRepository;

    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void resetCreatedUserTracking() {
        createdUserIds.clear();
    }

    @AfterEach
    void clearRfmForCreatedUsersOnly() {
        for (Long userId : createdUserIds) {
            userSummaryRepository.findById(userId).ifPresent(summary -> {
                summary.updateRfmSegment(null);
                userSummaryRepository.save(summary);
            });
        }
    }

    private Fixture fixture(String suffix) throws Exception {
        RfmSegment targetSegment = suffix.equals("auto") ? RfmSegment.VIP : RfmSegment.LOYAL;
        AudienceSegment segment = segmentRepository.save(AudienceSegment.builder()
                .name("run-regression-" + suffix + "-" + UUID.randomUUID())
                .targetRfmSegment(targetSegment).isActive(true).build());
        Campaign campaign = campaignRepository.save(Campaign.builder()
                .name("Run regression " + suffix)
                .startAt(LocalDateTime.now().minusDays(1)).endAt(LocalDateTime.now().plusDays(1))
                .targetSegmentId(segment.getId()).budget(new BigDecimal("10000")).build());
        Coupon coupon = couponRepository.save(Coupon.builder()
                .name("Run regression coupon " + suffix).discountAmount(new BigDecimal("10"))
                .startDate(LocalDateTime.now().minusDays(1)).endDate(LocalDateTime.now().plusDays(1)).build());

        for (int i = 0; i < 10; i++) {
            User user = userRepository.save(User.builder().name("Run user " + suffix + " " + i)
                    .email("run-" + suffix + "-" + UUID.randomUUID() + "@example.test")
                    .role(Role.USER).build());
            createdUserIds.add(user.getId());
            UserSummary summary = user.getUserSummary();
            summary.updateRfmSegment(targetSegment);
            userSummaryRepository.save(summary);
        }

        LocalDateTime start = LocalDateTime.now().minusHours(1);
        LocalDateTime end = LocalDateTime.now().plusHours(1);
        String activityBody = objectMapper.writeValueAsString(Map.ofEntries(
                Map.entry("name", "Direct coupon run " + suffix),
                Map.entry("limitCount", 10), Map.entry("status", "ACTIVE"),
                Map.entry("startDate", start.toString()), Map.entry("endDate", end.toString()),
                Map.entry("activityType", "COUPON"), Map.entry("couponId", coupon.getId()),
                Map.entry("couponDeliveryMode", "DIRECT"), Map.entry("budget", 1000),
                Map.entry("expectedRecipientCount", 10), Map.entry("maxRecipientCount", 10),
                Map.entry("holdoutRatePercent", 20), Map.entry("purpose", targetSegment == RfmSegment.VIP
                        ? CampaignActivityPurpose.VIP_REWARD.name() : CampaignActivityPurpose.LOYALTY_REWARD.name()),
                Map.entry("operatorMemo", "Phase 1 regression fixture")));
        MvcResult activityResult = mockMvc.perform(post("/api/v1/campaigns/{campaignId}/activities", campaign.getId())
                        .with(user("phase-one-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(activityBody))
                .andExpect(status().isCreated()).andReturn();
        long activityId = objectMapper.readTree(activityResult.getResponse().getContentAsString()).path("id").asLong();
        return new Fixture(campaign.getId(), activityId, coupon.getId());
    }

    @Test
    void noFlagAutomaticallyIssuesOnlyTreatmentAndIsDuplicateSafe() throws Exception {
        Fixture fixture = fixture("auto");
        JsonNode run = prepareRun(fixture.activityId());
        long runId = run.path("runId").asLong();
        assertThat(run.path("targetCount").asInt()).isEqualTo(10);
        assertThat(run.path("holdoutCount").asInt()).isEqualTo(2);
        List<CampaignActivityRunTarget> assignments = targetsFor(runId);
        List<Long> treatment = assignments.stream()
                .filter(t -> t.getAssignmentGroup() == CampaignActivityRunTargetGroup.TREATMENT)
                .map(CampaignActivityRunTarget::getUserId).toList();
        List<Long> holdout = assignments.stream()
                .filter(t -> t.getAssignmentGroup() == CampaignActivityRunTargetGroup.HOLDOUT)
                .map(CampaignActivityRunTarget::getUserId).toList();
        assertThat(assignments).hasSize(10);
        assertThat(treatment).hasSize(8);
        assertThat(holdout).hasSize(2);

        String claim = claim(runId);
        postInternal("/internal/v1/campaign-run-reviews/runs/" + runId + "/analysis",
                "{\"claimToken\":" + objectMapper.writeValueAsString(claim)
                        + ",\"recommendation\":\"NO_FLAG\",\"summary\":\"Controlled clear review\"}")
                .andExpect(status().isOk());
        postInternal("/internal/v1/campaign-run-reviews/runs/" + runId + "/auto-dispatch", "")
                .andExpect(status().isOk());

        awaitCoupons(fixture, runId, treatment.size());
        assertIssuedTo(fixture, runId, treatment, holdout);
        assertExecutionAndDispatchSuccess(runId, treatment.size());

        List<Long> executionIdsBeforeRepeat = executionIdsForRun(runId);
        List<Long> dispatchIdsBeforeRepeat = dispatchIdsForRun(runId);
        int before = userCouponRepository.findAllByUserIdInAndCouponIdIn(
                assignments.stream().map(CampaignActivityRunTarget::getUserId).toList(), List.of(fixture.couponId())).size();
        int repeatStatus = postInternal("/internal/v1/campaign-run-reviews/runs/" + runId + "/auto-dispatch", "")
                .andReturn().getResponse().getStatus();
        assertThat(repeatStatus).isIn(200, 409);
        assertThat(userCouponRepository.findAllByUserIdInAndCouponIdIn(
                assignments.stream().map(CampaignActivityRunTarget::getUserId).toList(), List.of(fixture.couponId())))
                .hasSize(before);
        assertThat(executionIdsForRun(runId)).containsExactlyElementsOf(executionIdsBeforeRepeat);
        assertThat(dispatchIdsForRun(runId)).containsExactlyElementsOf(dispatchIdsBeforeRepeat);
    }

    @Test
    void flaggedRunWaitsForApprovalThenIssuesOnceWithAttribution() throws Exception {
        Fixture fixture = fixture("approval");
        JsonNode run = prepareRun(fixture.activityId());
        long runId = run.path("runId").asLong();
        List<CampaignActivityRunTarget> assignments = targetsFor(runId);
        List<Long> treatment = assignments.stream()
                .filter(t -> t.getAssignmentGroup() == CampaignActivityRunTargetGroup.TREATMENT)
                .map(CampaignActivityRunTarget::getUserId).toList();
        List<Long> holdout = assignments.stream()
                .filter(t -> t.getAssignmentGroup() == CampaignActivityRunTargetGroup.HOLDOUT)
                .map(CampaignActivityRunTarget::getUserId).toList();
        assertThat(assignments).hasSize(10);
        assertThat(treatment).hasSize(8);
        assertThat(holdout).hasSize(2);

        String claim = claim(runId);
        postInternal("/internal/v1/campaign-run-reviews/runs/" + runId + "/analysis",
                "{\"claimToken\":" + objectMapper.writeValueAsString(claim)
                        + ",\"recommendation\":\"FLAG\",\"summary\":\"Controlled flagged review\"}")
                .andExpect(status().isOk());
        postInternal("/internal/v1/campaign-run-reviews/runs/" + runId + "/auto-dispatch", "")
                .andExpect(status().isConflict());
        assertThat(executionRepository.existsByCampaignActivityRunIdAndUserId(runId, treatment.get(0))).isFalse();
        assertThat(executionIdsForRun(runId)).isEmpty();
        assertThat(dispatchIdsForRun(runId)).isEmpty();
        assertThat(userCouponRepository.findAllByUserIdInAndCouponIdIn(assignments.stream()
                .map(CampaignActivityRunTarget::getUserId).toList(), List.of(fixture.couponId()))).isEmpty();

        postInternal("/internal/v1/campaign-run-reviews/runs/" + runId + "/decision",
                "{\"decision\":\"APPROVE\",\"decidedBy\":\"operator-phase-one\",\"reason\":\"Reviewed and approved\"}")
                .andExpect(status().isOk());
        awaitCoupons(fixture, runId, treatment.size());
        assertIssuedTo(fixture, runId, treatment, holdout);
        assertExecutionAndDispatchSuccess(runId, treatment.size());
        CampaignActivityRun persisted = runRepository.findById(runId).orElseThrow();
        assertThat(persisted.getDecidedBy()).isEqualTo("operator-phase-one");

        List<Long> executionIdsBeforeRepeat = executionIdsForRun(runId);
        List<Long> dispatchIdsBeforeRepeat = dispatchIdsForRun(runId);
        postInternal("/internal/v1/campaign-run-reviews/runs/" + runId + "/decision",
                "{\"decision\":\"APPROVE\",\"decidedBy\":\"operator-phase-one\",\"reason\":\"Repeated approval\"}")
                .andExpect(status().isConflict());
        assertThat(userCouponRepository.findAllByUserIdInAndCouponIdIn(assignments.stream()
                .map(CampaignActivityRunTarget::getUserId).toList(), List.of(fixture.couponId())))
                .hasSize(treatment.size());
        assertThat(executionIdsForRun(runId)).containsExactlyElementsOf(executionIdsBeforeRepeat);
        assertThat(dispatchIdsForRun(runId)).containsExactlyElementsOf(dispatchIdsBeforeRepeat);
    }

    private JsonNode prepareRun(long activityId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/campaign-activities/{activityId}/runs", activityId)
                        .with(user("phase-one-admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String claim(long runId) throws Exception {
        MvcResult result = postInternal("/internal/v1/campaign-run-reviews/claim", "")
                .andExpect(status().isOk()).andReturn();
        JsonNode claim = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(claim.path("runId").asLong()).isEqualTo(runId);
        return claim.path("analysisClaimToken").asText();
    }

    private org.springframework.test.web.servlet.ResultActions postInternal(String path, String body) throws Exception {
        var request = post(path).header("X-Axon-Triage-Token", INTERNAL_TOKEN);
        if (!body.isEmpty()) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(request);
    }

    private void awaitCoupons(Fixture fixture, long runId, int expected) {
        Awaitility.await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(userCouponRepository.findAllByUserIdInAndCouponIdIn(
                        targetsFor(runId).stream().map(CampaignActivityRunTarget::getUserId).toList(),
                        List.of(fixture.couponId()))).hasSize(expected));
    }

    private void assertIssuedTo(Fixture fixture, long runId, List<Long> treatment, List<Long> holdout) {
        List<Long> assignedUsers = java.util.stream.Stream.concat(treatment.stream(), holdout.stream()).toList();
        List<UserCoupon> coupons = userCouponRepository.findAllByUserIdInAndCouponIdIn(
                assignedUsers, List.of(fixture.couponId()));
        assertThat(coupons).hasSize(treatment.size());
        assertThat(coupons).extracting(UserCoupon::getUserId).containsExactlyInAnyOrderElementsOf(treatment);
        assertThat(coupons).extracting(UserCoupon::getUserId).doesNotContainAnyElementsOf(holdout);
        assertThat(coupons).extracting(UserCoupon::getUserId).doesNotHaveDuplicates();
        assertThat(executionRepository.findAll()).filteredOn(e -> java.util.Objects.equals(
                runId, e.getCampaignActivityRunId()))
                .extracting(e -> e.getUserId()).containsExactlyInAnyOrderElementsOf(treatment);
    }

    private void assertExecutionAndDispatchSuccess(long runId, int expected) {
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            List<MarketingActionDispatch> dispatches = dispatchesForRun(runId);
            assertThat(dispatches).hasSize(expected);
            assertThat(dispatches).allSatisfy(d -> assertThat(d.getStatus().name()).isEqualTo("SUCCEEDED"));
        });
    }

    private List<Long> executionIdsForRun(long runId) {
        return executionRepository.findAll().stream()
                .filter(execution -> java.util.Objects.equals(runId, execution.getCampaignActivityRunId()))
                .map(execution -> execution.getId()).sorted().toList();
    }

    private List<Long> dispatchIdsForRun(long runId) {
        return dispatchesForRun(runId).stream().map(MarketingActionDispatch::getId).sorted().toList();
    }

    private List<MarketingActionDispatch> dispatchesForRun(long runId) {
        return executionIdsForRun(runId).stream()
                .flatMap(id -> dispatchRepository.findAllByExecution_IdOrderBySequenceAsc(id).stream()).toList();
    }

    private List<CampaignActivityRunTarget> targetsFor(long runId) {
        return targetRepository.findAll().stream().filter(target -> target.getRunId() == runId).toList();
    }

    private record Fixture(Long campaignId, Long activityId, Long couponId) { }
}
