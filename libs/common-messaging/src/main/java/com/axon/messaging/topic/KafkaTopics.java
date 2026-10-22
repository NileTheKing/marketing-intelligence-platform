package com.axon.messaging.topic;

public final class KafkaTopics {

    /**
     * Prevents instantiation of this utility class containing Kafka topic name constants.
     */
    private KafkaTopics() {
    }

    //logging topics
    public static final String BEHAVIOR_EVENT = "axon.event.behavior";
    public static final String COMMERCE_EVENT = "axon.event.commerce";

    //cqrs topics
    public static final String CAMPAIGN_ACTIVITY_COMMAND = "axon.campaign-activity.command";
    public static final String WEBHOOK_COMMAND = "axon.webhook.command";

    // Dead Letter Topics (DLT) for Fault Tolerance
    public static final String CAMPAIGN_ACTIVITY_COMMAND_DLT = "axon.campaign-activity.command.dlt";
    public static final String USER_SUMMARY_PROJECTION_FAILED = "axon.projection.user-summary.failed";
    public static final String WEBHOOK_FAILED_DLT = "axon.webhook.failed.dlt";

    @Deprecated
    public static final String EVENT_RAW = "axon.event.raw";
    @Deprecated
    public static final String PAYMENT_RETRY_TOPIC = "axon.payment.retry";
    @Deprecated
    public static final String USER_LOGIN = "axon.user.login";
}
