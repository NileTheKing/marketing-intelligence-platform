package com.axon.core_service.commandprocessing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.condition.EmbeddedKafkaCondition;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;

@EmbeddedKafka(partitions = 1, topics = {
        WebhookTopicIsolationExperiment.SHARED_TOPIC,
        WebhookTopicIsolationExperiment.INTERNAL_TOPIC,
        WebhookTopicIsolationExperiment.WEBHOOK_TOPIC
})
class WebhookTopicIsolationExperiment {

    static final String SHARED_TOPIC = "experiment.command.shared";
    static final String INTERNAL_TOPIC = "experiment.command.internal";
    static final String WEBHOOK_TOPIC = "experiment.command.webhook";

    private static final long WEBHOOK_DELAY_MILLIS = 2_000;
    private static EmbeddedKafkaBroker broker;

    @BeforeAll
    static void setUpBroker() {
        broker = EmbeddedKafkaCondition.getBroker();
    }

    @Test
    void delayedWebhookBlocksSharedTopicButNotIsolatedFcfsConsumer() throws Exception {
        DefaultKafkaProducerFactory<String, String> producerFactory =
                new DefaultKafkaProducerFactory<>(producerProperties());
        try (AdminClient adminClient = AdminClient.create(Map.of(
                     AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
            KafkaTemplate<String, String> kafkaTemplate = new KafkaTemplate<>(producerFactory);

            for (int run = 1; run <= 3; run++) {
                SharedResult before = runSharedTopic(kafkaTemplate, adminClient, run);
                IsolatedResult after = runIsolatedTopics(kafkaTemplate, adminClient, run);

                System.out.printf(
                        "WEBHOOK_TOPIC_AB run=%d before.fcfsLatencyMs=%d "
                                + "before.offsetBaseline=%d before.offsetDuringWebhook=%d "
                                + "after.fcfsLatencyMs=%d after.internalOffsetBaseline=%d "
                                + "after.internalOffset=%d after.webhookOffsetBaseline=%d "
                                + "after.webhookOffsetDuringDelay=%d "
                                + "after.webhookStillRunning=%s%n",
                        run,
                        before.fcfsLatencyMillis(),
                        before.offsetBaseline(),
                        before.offsetDuringWebhook(),
                        after.fcfsLatencyMillis(),
                        after.internalOffsetBaseline(),
                        after.internalOffset(),
                        after.webhookOffsetBaseline(),
                        after.webhookOffsetDuringDelay(),
                        after.webhookStillRunning());

                assertThat(before.fcfsLatencyMillis()).isGreaterThanOrEqualTo(1_500);
                assertThat(before.offsetDuringWebhook()).isLessThanOrEqualTo(before.offsetBaseline());
                assertThat(after.fcfsLatencyMillis()).isLessThan(1_000);
                assertThat(after.internalOffset()).isEqualTo(after.internalOffsetBaseline() + 1);
                assertThat(after.webhookOffsetDuringDelay())
                        .isLessThanOrEqualTo(after.webhookOffsetBaseline());
                assertThat(after.webhookStillRunning()).isTrue();
            }
        } finally {
            producerFactory.destroy();
        }
    }

    private SharedResult runSharedTopic(
            KafkaTemplate<String, String> kafkaTemplate,
            AdminClient adminClient,
            int run
    ) throws Exception {
        String groupId = "experiment-shared-" + run;
        long offsetBaseline = endOffset(adminClient, SHARED_TOPIC);
        CountDownLatch webhookStarted = new CountDownLatch(1);
        CountDownLatch fcfsCompleted = new CountDownLatch(1);
        AtomicLong fcfsCompletedAt = new AtomicLong();

        KafkaMessageListenerContainer<String, String> container = container(
                SHARED_TOPIC,
                groupId,
                record -> {
                    if ("WEBHOOK".equals(record.value())) {
                        webhookStarted.countDown();
                        sleep(WEBHOOK_DELAY_MILLIS);
                    }
                    if ("FCFS".equals(record.value())) {
                        fcfsCompletedAt.set(System.nanoTime());
                        fcfsCompleted.countDown();
                    }
                });

        try {
            kafkaTemplate.send(SHARED_TOPIC, "WEBHOOK").get(5, TimeUnit.SECONDS);
            assertThat(webhookStarted.await(5, TimeUnit.SECONDS)).isTrue();

            long fcfsSentAt = System.nanoTime();
            kafkaTemplate.send(SHARED_TOPIC, "FCFS").get(5, TimeUnit.SECONDS);
            long offsetDuringWebhook = committedOffset(adminClient, groupId, SHARED_TOPIC);

            assertThat(fcfsCompleted.await(5, TimeUnit.SECONDS)).isTrue();
            return new SharedResult(
                    elapsedMillis(fcfsSentAt, fcfsCompletedAt.get()),
                    offsetBaseline,
                    offsetDuringWebhook);
        } finally {
            container.stop();
        }
    }

    private IsolatedResult runIsolatedTopics(
            KafkaTemplate<String, String> kafkaTemplate,
            AdminClient adminClient,
            int run
    ) throws Exception {
        String internalGroupId = "experiment-internal-" + run;
        String webhookGroupId = "experiment-webhook-" + run;
        long internalOffsetBaseline = endOffset(adminClient, INTERNAL_TOPIC);
        long webhookOffsetBaseline = endOffset(adminClient, WEBHOOK_TOPIC);
        CountDownLatch webhookStarted = new CountDownLatch(1);
        CountDownLatch webhookCompleted = new CountDownLatch(1);
        CountDownLatch fcfsCompleted = new CountDownLatch(1);
        AtomicLong fcfsCompletedAt = new AtomicLong();

        KafkaMessageListenerContainer<String, String> webhookContainer = container(
                WEBHOOK_TOPIC,
                webhookGroupId,
                record -> {
                    webhookStarted.countDown();
                    sleep(WEBHOOK_DELAY_MILLIS);
                    webhookCompleted.countDown();
                });
        KafkaMessageListenerContainer<String, String> internalContainer = container(
                INTERNAL_TOPIC,
                internalGroupId,
                record -> {
                    fcfsCompletedAt.set(System.nanoTime());
                    fcfsCompleted.countDown();
                });

        try {
            kafkaTemplate.send(WEBHOOK_TOPIC, "WEBHOOK").get(5, TimeUnit.SECONDS);
            assertThat(webhookStarted.await(5, TimeUnit.SECONDS)).isTrue();

            long fcfsSentAt = System.nanoTime();
            kafkaTemplate.send(INTERNAL_TOPIC, "FCFS").get(5, TimeUnit.SECONDS);
            assertThat(fcfsCompleted.await(1, TimeUnit.SECONDS)).isTrue();

            long internalOffset = awaitCommittedOffset(
                    adminClient, internalGroupId, INTERNAL_TOPIC, 1, Duration.ofSeconds(2));
            long webhookOffsetDuringDelay = committedOffset(
                    adminClient, webhookGroupId, WEBHOOK_TOPIC);

            return new IsolatedResult(
                    elapsedMillis(fcfsSentAt, fcfsCompletedAt.get()),
                    internalOffsetBaseline,
                    internalOffset,
                    webhookOffsetBaseline,
                    webhookOffsetDuringDelay,
                    webhookCompleted.getCount() == 1);
        } finally {
            internalContainer.stop();
            webhookContainer.stop();
        }
    }

    private KafkaMessageListenerContainer<String, String> container(
            String topic,
            String groupId,
            MessageListener<String, String> listener
    ) {
        Map<String, Object> properties = KafkaTestUtils.consumerProps(groupId, "false", broker);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 20);

        DefaultKafkaConsumerFactory<String, String> consumerFactory =
                new DefaultKafkaConsumerFactory<>(
                        properties,
                        new StringDeserializer(),
                        new StringDeserializer());
        ContainerProperties containerProperties = new ContainerProperties(topic);
        containerProperties.setAckMode(ContainerProperties.AckMode.BATCH);
        KafkaMessageListenerContainer<String, String> container =
                new KafkaMessageListenerContainer<>(consumerFactory, containerProperties);
        container.setupMessageListener(listener);
        container.start();
        ContainerTestUtils.waitForAssignment(container, 1);
        return container;
    }

    private Map<String, Object> producerProperties() {
        Map<String, Object> properties = KafkaTestUtils.producerProps(broker);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return properties;
    }

    private long awaitCommittedOffset(
            AdminClient adminClient,
            String groupId,
            String topic,
            long expected,
            Duration timeout
    ) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        long offset;
        do {
            offset = committedOffset(adminClient, groupId, topic);
            if (offset >= expected) {
                return offset;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        return offset;
    }

    private long committedOffset(AdminClient adminClient, String groupId, String topic)
            throws Exception {
        var offsets = adminClient.listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata()
                .get(2, TimeUnit.SECONDS);
        var offset = offsets.get(new TopicPartition(topic, 0));
        return offset == null ? -1 : offset.offset();
    }

    private long endOffset(AdminClient adminClient, String topic) throws Exception {
        TopicPartition partition = new TopicPartition(topic, 0);
        return adminClient.listOffsets(Map.of(partition, OffsetSpec.latest()))
                .all()
                .get(2, TimeUnit.SECONDS)
                .get(partition)
                .offset();
    }

    private long elapsedMillis(long startedAt, long completedAt) {
        return TimeUnit.NANOSECONDS.toMillis(completedAt - startedAt);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private record SharedResult(
            long fcfsLatencyMillis,
            long offsetBaseline,
            long offsetDuringWebhook
    ) {
    }

    private record IsolatedResult(
            long fcfsLatencyMillis,
            long internalOffsetBaseline,
            long internalOffset,
            long webhookOffsetBaseline,
            long webhookOffsetDuringDelay,
            boolean webhookStillRunning
    ) {
    }
}
