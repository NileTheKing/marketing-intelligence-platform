package com.axon.core_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axon.core_service.domain.campaign.Campaign;
import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.campaignactivity.CampaignActivityPurpose;
import com.axon.core_service.domain.campaignactivity.CampaignActivityRun;
import com.axon.core_service.domain.campaignactivity.CampaignActivityRunStatus;
import com.axon.core_service.domain.coupon.Coupon;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityRunAnalysisRequest;
import com.axon.core_service.domain.marketing.MarketingAction;
import com.axon.core_service.domain.marketing.RewardType;
import com.axon.core_service.domain.user.RfmSegment;
import com.axon.core_service.repository.AudienceSegmentRepository;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.CampaignActivityRunRepository;
import com.axon.core_service.repository.CampaignActivityRunTargetRepository;
import com.axon.core_service.repository.UserSummaryRepository;
import com.axon.messaging.CampaignActivityType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CampaignActivityRunServiceTest {

    @Mock private CampaignActivityRepository activityRepository;
    @Mock private CampaignActivityRunRepository runRepository;
    @Mock private CampaignActivityRunTargetRepository targetRepository;
    @Mock private AudienceSegmentRepository audienceSegmentRepository;
    @Mock private UserSummaryRepository userSummaryRepository;
    @Mock private CampaignActivityRunDispatchClaimService dispatchClaimService;
    @Mock private MarketingActionExecutionService executionService;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void blocksRunBeforeAiOrKafkaWhenTargetsExceedCouponLimit() {
        CampaignActivity activity = couponActivity(100);
        when(activityRepository.findWithCampaignCouponAndActionByIdForUpdate(1L)).thenReturn(Optional.of(activity));
        when(audienceSegmentRepository.findById(10L)).thenReturn(Optional.of(
                com.axon.core_service.domain.marketing.AudienceSegment.builder()
                        .name("VIP").targetRfmSegment(RfmSegment.VIP).isActive(true).build()));
        when(userSummaryRepository.countCouponCampaignTargets(RfmSegment.VIP, 30L)).thenReturn(120L);
        when(runRepository.sumReservedTargetCount(org.mockito.ArgumentMatchers.eq(1L), anyList())).thenReturn(0L);
        when(runRepository.save(any(CampaignActivityRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().prepare(1L);

        assertThat(response.status()).isEqualTo(CampaignActivityRunStatus.BLOCKED.name());
        assertThat(response.blockedReason()).isEqualTo("Target count exceeds maximum recipient count");
        verify(targetRepository, never()).saveAll(any());
        verify(kafkaTemplate, never()).send(any(String.class), any());
    }

    @Test
    void blocksRunWhenEarlierRunsAlreadyReservedTheRemainingCouponCapacity() {
        CampaignActivity activity = couponActivity(100);
        when(activityRepository.findWithCampaignCouponAndActionByIdForUpdate(1L)).thenReturn(Optional.of(activity));
        when(audienceSegmentRepository.findById(10L)).thenReturn(Optional.of(
                com.axon.core_service.domain.marketing.AudienceSegment.builder()
                        .name("VIP").targetRfmSegment(RfmSegment.VIP).isActive(true).build()));
        when(userSummaryRepository.countCouponCampaignTargets(RfmSegment.VIP, 30L)).thenReturn(20L);
        when(runRepository.sumReservedTargetCount(org.mockito.ArgumentMatchers.eq(1L), anyList())).thenReturn(90L);
        when(runRepository.save(any(CampaignActivityRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().prepare(1L);

        assertThat(response.status()).isEqualTo(CampaignActivityRunStatus.BLOCKED.name());
        assertThat(response.blockedReason()).isEqualTo("Target count exceeds remaining coupon issuance limit");
        verify(targetRepository, never()).saveAll(any());
    }

    @Test
    void blocksDiscountMistakeWhenActualRecipientsExceedTheCampaignBudget() {
        CampaignActivity activity = couponActivity(200, BigDecimal.valueOf(50_000), BigDecimal.valueOf(1_000_000));
        activity.updateRecipientPolicy(120, 200);
        when(activityRepository.findWithCampaignCouponAndActionByIdForUpdate(1L)).thenReturn(Optional.of(activity));
        when(audienceSegmentRepository.findById(10L)).thenReturn(Optional.of(
                com.axon.core_service.domain.marketing.AudienceSegment.builder()
                        .name("VIP").targetRfmSegment(RfmSegment.VIP).isActive(true).build()));
        when(userSummaryRepository.countCouponCampaignTargets(RfmSegment.VIP, 30L)).thenReturn(120L);
        when(runRepository.sumReservedTargetCount(org.mockito.ArgumentMatchers.eq(1L), anyList())).thenReturn(0L);
        when(runRepository.save(any(CampaignActivityRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().prepare(1L);

        ArgumentCaptor<CampaignActivityRun> captor = ArgumentCaptor.forClass(CampaignActivityRun.class);
        verify(runRepository).save(captor.capture());
        assertThat(response.status()).isEqualTo(CampaignActivityRunStatus.BLOCKED.name());
        assertThat(response.blockedReason()).isEqualTo("Predicted coupon cost exceeds campaign budget");
        assertThat(captor.getValue().getFactSnapshot().get("predictedFixedCouponCost"))
                .isEqualTo("6000000");
        verify(targetRepository, never()).saveAll(any());
    }

    @Test
    void holdsRunForOperatorWhenCouponLimitChangesAfterReview() {
        CampaignActivity activity = couponActivity(100);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("couponId", 30L);
        facts.put("issuanceLimit", 100);
        facts.put("maxRecipientCount", 100);
        facts.put("expectedRecipientCount", 80);
        facts.put("campaignPurpose", "VIP_REWARD");
        facts.put("operatorMemo", "VIP 고객 감사 쿠폰");
        facts.put("budget", "1000");
        facts.put("couponDiscountAmount", "10");
        facts.put("couponDiscountRate", null);
        facts.put("targetSegmentId", 10L);
        facts.put("codeFlags", java.util.List.of());
        CampaignActivityRun run = CampaignActivityRun.pendingAnalysis(activity, 80, facts);
        run.claimAnalysis("claim", LocalDateTime.now().plusMinutes(1));
        run.saveAnalysis("ready", "NO_FLAG");
        activity.updateInfo("coupon campaign", 50);

        when(runRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(run));
        when(activityRepository.findWithCampaignCouponAndActionByIdForUpdate(1L)).thenReturn(Optional.of(activity));

        var messages = dispatchService().claim(99L, "U_ADMIN");

        assertThat(messages).isEmpty();
        assertThat(run.getStatus()).isEqualTo(CampaignActivityRunStatus.AWAITING_APPROVAL);
        assertThat(run.getRecommendation()).isEqualTo("FLAG");
        assertThat(run.getBlockedReason()).isEqualTo("Campaign activity configuration changed after review");
        verify(targetRepository, never()).findAllByRunIdAndExecutionIdIsNullOrderByUserIdAsc(any());
    }

    @Test
    void turnsAiNoFlagIntoFlagWhenCoreFactsContainRiskSignals() {
        CampaignActivity activity = couponActivity(100);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("codeFlags", java.util.List.of("TARGET_COUNT_EXCEEDS_EXPECTATION"));
        CampaignActivityRun run = CampaignActivityRun.pendingAnalysis(activity, 80, facts);
        run.claimAnalysis("claim", LocalDateTime.now().plusMinutes(1));

        when(runRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(run));

        service().saveAnalysis(99L, new CampaignActivityRunAnalysisRequest("claim", "NO_FLAG", "문제 없음"));

        assertThat(run.getRecommendation()).isEqualTo("FLAG");
        assertThat(run.getStatus()).isEqualTo(CampaignActivityRunStatus.AWAITING_APPROVAL);
    }

    @Test
    void recordsTargetGrowthAsACodeFlagBeforeAiReview() {
        CampaignActivity activity = couponActivity(100);
        when(activityRepository.findWithCampaignCouponAndActionByIdForUpdate(1L)).thenReturn(Optional.of(activity));
        when(audienceSegmentRepository.findById(10L)).thenReturn(Optional.of(
                com.axon.core_service.domain.marketing.AudienceSegment.builder()
                        .name("VIP").targetRfmSegment(RfmSegment.VIP).isActive(true).build()));
        when(userSummaryRepository.countCouponCampaignTargets(RfmSegment.VIP, 30L)).thenReturn(81L);
        when(userSummaryRepository.findCouponCampaignTargetUserIds(any(), any(), any()))
                .thenReturn(java.util.stream.LongStream.rangeClosed(1, 81).boxed().toList());
        when(runRepository.sumReservedTargetCount(org.mockito.ArgumentMatchers.eq(1L), anyList())).thenReturn(0L);
        when(runRepository.save(any(CampaignActivityRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().prepare(1L);

        ArgumentCaptor<CampaignActivityRun> captor = ArgumentCaptor.forClass(CampaignActivityRun.class);
        verify(runRepository).save(captor.capture());
        assertThat(response.status()).isEqualTo(CampaignActivityRunStatus.PENDING_ANALYSIS.name());
        assertThat(captor.getValue().getFactSnapshot().get("codeFlags"))
                .isEqualTo(java.util.List.of("TARGET_COUNT_EXCEEDS_EXPECTATION"));
    }

    private CampaignActivityRunService service() {
        return new CampaignActivityRunService(activityRepository, runRepository, targetRepository,
                audienceSegmentRepository, userSummaryRepository, dispatchClaimService,
                executionService, kafkaTemplate);
    }

    private CampaignActivityRunDispatchClaimService dispatchService() {
        return new CampaignActivityRunDispatchClaimService(runRepository, targetRepository,
                activityRepository, userSummaryRepository, audienceSegmentRepository, executionService);
    }

    private CampaignActivity couponActivity(int limit) {
        return couponActivity(limit, BigDecimal.TEN, BigDecimal.valueOf(1000));
    }

    private CampaignActivity couponActivity(int limit, BigDecimal discountAmount, BigDecimal budget) {
        LocalDateTime now = LocalDateTime.now();
        Campaign campaign = Campaign.builder().name("autumn sale").targetSegmentId(10L).build();
        Coupon coupon = Coupon.builder().name("welcome").discountAmount(discountAmount)
                .startDate(now.minusDays(1)).endDate(now.plusDays(1)).build();
        MarketingAction action = MarketingAction.builder().marketingRule(null)
                .actionType(RewardType.COUPON).referenceId(30L).isActive(true).build();
        ReflectionTestUtils.setField(coupon, "id", 30L);
        ReflectionTestUtils.setField(action, "id", 20L);
        CampaignActivity activity = CampaignActivity.builder()
                .campaign(campaign)
                .name("coupon campaign")
                .limitCount(limit)
                .maxRecipientCount(limit)
                .expectedRecipientCount(80)
                .purpose(CampaignActivityPurpose.VIP_REWARD)
                .operatorMemo("VIP 고객 감사 쿠폰")
                .budget(budget)
                .status(CampaignActivityStatus.ACTIVE)
                .startDate(now.minusHours(1))
                .endDate(now.plusHours(1))
                .activityType(CampaignActivityType.COUPON)
                .coupon(coupon)
                .marketingAction(action)
                .price(BigDecimal.ZERO)
                .quantity(0)
                .build();
        ReflectionTestUtils.setField(activity, "id", 1L);
        return activity;
    }
}
