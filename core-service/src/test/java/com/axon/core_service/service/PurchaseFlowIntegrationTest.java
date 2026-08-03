package com.axon.core_service.service;

import com.axon.core_service.AbstractIntegrationTest;
import com.axon.core_service.commandprocessing.FcfsLedgerPersistenceService;
import com.axon.core_service.commandprocessing.FcfsCommandOrchestrator;
import com.axon.core_service.domain.campaignactivityentry.CampaignActivityEntry;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.core_service.domain.campaign.Campaign;
import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.domain.product.Product;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.CampaignRepository;
import com.axon.core_service.repository.CampaignActivityEntryRepository;
import com.axon.core_service.repository.ProductRepository;
import com.axon.core_service.repository.UserRepository;
import com.axon.core_service.repository.UserSummaryRepository;
import com.axon.core_service.domain.user.User;
import com.axon.core_service.domain.user.Role;
import com.axon.messaging.topic.KafkaTopics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.beans.factory.annotation.Value;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Instant;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

@DisplayName("End-to-End: Kafka to MySQL 구매 플로우 통합 테스트")
public class PurchaseFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${spring.kafka.bootstrap-servers}")
    private String kafkaBootstrapServers;

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private CampaignActivityRepository campaignActivityRepository;

    @Autowired
    private CampaignActivityEntryRepository campaignActivityEntryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private FcfsLedgerPersistenceService ledgerPersistenceService;

    @Autowired
    private FcfsCommandOrchestrator orchestrator;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserSummaryRepository userSummaryRepository;

    @SpyBean
    private PurchaseRepository purchaseRepositorySpy;

    private Long activityId;
    private Long productId;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.reset(purchaseRepositorySpy);
        purchaseRepository.deleteAll();
        campaignActivityEntryRepository.deleteAll();
        campaignActivityRepository.deleteAll();
        campaignRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();

        Campaign campaign = Campaign.builder()
                .name("E2E Test Campaign")
                .build();
        campaign = campaignRepository.save(campaign);

        Product product = new Product("E2E Test Product", 100L, BigDecimal.valueOf(10000), "General");
        product = productRepository.save(product);
        this.productId = product.getId();

        CampaignActivity activity = CampaignActivity.builder()
                .campaign(campaign)
                .product(product)
                .name("E2E FCFS Activity")
                .limitCount(100)
                .status(CampaignActivityStatus.ACTIVE)
                .startDate(LocalDateTime.now())
                .endDate(LocalDateTime.now().plusDays(1))
                .activityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .price(BigDecimal.valueOf(10000))
                .quantity(100)
                .build();
        activity = campaignActivityRepository.save(activity);
        this.activityId = activity.getId();
    }

    @Test
    @DisplayName("Kafka 메시지 발행 시 Core Service가 소모하여 DB에 Purchase를 저장해야 함")
    void kafkaToPurchaseIntegrationTest() {
        // given
        Long userId = 777L;
        CampaignActivityKafkaProducerDto dto = CampaignActivityKafkaProducerDto.builder()
                .userId(userId)
                .productId(productId)
                .campaignActivityId(activityId)
                .campaignActivityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .timestamp(System.currentTimeMillis())
                .build();

        // when: Kafka 전송
        kafkaTemplate.send("axon.campaign-activity.command", dto);

        // then: 비동기 처리를 기다려 DB 적재 확인 (Awaitility 사용)
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    List<Purchase> purchases = purchaseRepository.findByUserIdIn(List.of(userId));
                    assertThat(purchases).isNotEmpty();
                    assertThat(purchases.get(0).getUserId()).isEqualTo(userId);
                    assertThat(purchases.get(0).getProductId()).isEqualTo(productId);
                });
    }

    @Test
    @DisplayName("FCFS 원장은 Entry와 Purchase를 한 트랜잭션으로 함께 저장하고 재전달에 수렴한다")
    void ledgerPersistsEntryAndPurchaseAtomicallyAndIdempotently() {
        CampaignActivityKafkaProducerDto dto = CampaignActivityKafkaProducerDto.builder()
                .userId(778L)
                .productId(productId)
                .campaignActivityId(activityId)
                .campaignActivityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .timestamp(System.currentTimeMillis())
                .build();

        ledgerPersistenceService.persistBatch(List.of(dto));
        ledgerPersistenceService.persistBatch(List.of(dto));

        assertThat(campaignActivityEntryRepository.count()).isEqualTo(1);
        assertThat(purchaseRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("FCFS 배치에 원장 저장 불가 메시지가 있으면 Entry와 Purchase를 모두 롤백한다")
    void ledgerRollsBackBothTablesWhenBatchCannotBePersisted() {
        CampaignActivityKafkaProducerDto valid = CampaignActivityKafkaProducerDto.builder()
                .userId(779L).productId(productId).campaignActivityId(activityId)
                .campaignActivityType(CampaignActivityType.FIRST_COME_FIRST_SERVE).build();
        CampaignActivityKafkaProducerDto invalid = CampaignActivityKafkaProducerDto.builder()
                .userId(780L).productId(productId).campaignActivityId(Long.MAX_VALUE)
                .campaignActivityType(CampaignActivityType.FIRST_COME_FIRST_SERVE).build();

        assertThatThrownBy(() -> ledgerPersistenceService.persistBatch(List.of(valid, invalid)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(campaignActivityEntryRepository.count()).isZero();
        assertThat(purchaseRepository.count()).isZero();
    }

    @Test
    @DisplayName("Purchase flush 실패는 이미 flush된 Entry도 같은 물리 트랜잭션에서 롤백한다")
    void purchaseFlushFailureRollsBackFlushedEntry() {
        CampaignActivityKafkaProducerDto dto = dto(781L);
        org.mockito.Mockito.doThrow(new IllegalStateException("purchase flush failure"))
                .when(purchaseRepositorySpy).saveAllAndFlush(any());

        assertThatThrownBy(() -> ledgerPersistenceService.persistBatch(List.of(dto)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(campaignActivityEntryRepository.count()).isZero();
        assertThat(purchaseRepository.count()).isZero();
    }

    @Test
    @DisplayName("FCFS Entry/Purchase 네 가지 기존 상태가 모두 하나의 쌍으로 수렴한다")
    void ledgerConvergesAllExistingStates() {
        CampaignActivityKafkaProducerDto neither = dto(790L);
        CampaignActivityKafkaProducerDto entryOnly = dto(791L);
        CampaignActivityKafkaProducerDto purchaseOnly = dto(792L);
        CampaignActivityKafkaProducerDto both = dto(793L);
        campaignActivityEntryRepository.save(CampaignActivityEntry.create(
                campaignActivityRepository.getReferenceById(activityId), entryOnly.getUserId(), productId, Instant.now()));
        purchaseRepository.save(purchase(purchaseOnly.getUserId()));
        campaignActivityEntryRepository.save(CampaignActivityEntry.create(
                campaignActivityRepository.getReferenceById(activityId), both.getUserId(), productId, Instant.now()));
        purchaseRepository.save(purchase(both.getUserId()));

        ledgerPersistenceService.persistBatch(List.of(neither, entryOnly, purchaseOnly, both));

        for (CampaignActivityKafkaProducerDto message : List.of(neither, entryOnly, purchaseOnly, both)) {
            assertThat(campaignActivityEntryRepository
                    .findByCampaignActivity_IdAndUserId(activityId, message.getUserId())).isPresent();
            assertThat(purchaseRepository.findByCampaignActivityId(activityId))
                    .anyMatch(purchase -> purchase.getUserId().equals(message.getUserId()));
        }
    }

    @Test
    @DisplayName("실제 Spring DB 배치에서 poison 한 건만 DLT로 격리하고 정상 19건은 원장에 저장한다")
    void poisonMessageIsolatedWhileNineteenLedgerPairsCommit() {
        List<CampaignActivityKafkaProducerDto> validMessages = java.util.stream.IntStream.range(0, 19)
                .mapToObj(index -> dto(800L + index)).toList();
        CampaignActivityKafkaProducerDto poison = CampaignActivityKafkaProducerDto.builder()
                .userId(999L).campaignActivityId(activityId)
                .campaignActivityType(CampaignActivityType.FIRST_COME_FIRST_SERVE).build();
        List<CampaignActivityKafkaProducerDto> batch = new java.util.ArrayList<>(validMessages);
        batch.add(poison);

        try (KafkaConsumer<String, byte[]> dltConsumer = dltConsumer()) {
            dltConsumer.subscribe(List.of(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT));
            dltConsumer.poll(Duration.ofSeconds(2)); // assignment before producing the poison record
            orchestrator.process(batch);

            ConsumerRecords<String, byte[]> records = dltConsumer.poll(Duration.ofSeconds(5));
            assertThat(records.count()).isEqualTo(1);
            assertThat(new String(records.iterator().next().value(), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("999");
        }

        for (CampaignActivityKafkaProducerDto message : validMessages) {
            assertThat(campaignActivityEntryRepository
                    .findByCampaignActivity_IdAndUserId(activityId, message.getUserId())).isPresent();
            assertThat(purchaseRepository.findByCampaignActivityId(activityId))
                    .anyMatch(purchase -> purchase.getUserId().equals(message.getUserId()));
        }
        assertThat(campaignActivityEntryRepository
                .findByCampaignActivity_IdAndUserId(activityId, poison.getUserId())).isEmpty();
        assertThat(purchaseRepository.findByCampaignActivityId(activityId))
                .noneMatch(purchase -> purchase.getUserId().equals(poison.getUserId()));
    }

    @Test
    @DisplayName("같은 업무키의 나중 요청은 기존 durable Purchase 시각을 임의로 전진시키지 않는다")
    void redeliveryWithLaterRequestDoesNotAdvanceLastPurchaseAt() {
        User user = userRepository.save(User.builder().name("summary-user").email("summary@example.com")
                .role(Role.USER).build());
        Instant durablePurchaseAt = Instant.parse("2026-08-01T00:00:00Z");
        CampaignActivityKafkaProducerDto first = dto(user.getId(), durablePurchaseAt);
        CampaignActivityKafkaProducerDto redelivery = dto(user.getId(), durablePurchaseAt.plusSeconds(3600));

        orchestrator.process(List.of(first));
        orchestrator.process(List.of(redelivery));

        assertThat(userSummaryRepository.findById(user.getId()).orElseThrow().getLastPurchaseAt())
                .isEqualTo(LocalDateTime.ofInstant(durablePurchaseAt, java.time.ZoneId.of("Asia/Seoul")));
    }

    private CampaignActivityKafkaProducerDto dto(Long userId) {
        return dto(userId, Instant.now());
    }

    private CampaignActivityKafkaProducerDto dto(Long userId, Instant occurredAt) {
        return CampaignActivityKafkaProducerDto.builder().userId(userId).productId(productId)
                .campaignActivityId(activityId).campaignActivityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .timestamp(occurredAt.toEpochMilli()).build();
    }

    private Purchase purchase(Long userId) {
        return new Purchase(userId, productId, activityId, PurchaseType.CAMPAIGNACTIVITY,
                BigDecimal.valueOf(10000), 1, Instant.now());
    }

    private KafkaConsumer<String, byte[]> dltConsumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "purchase-flow-dlt-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class));
    }
}
