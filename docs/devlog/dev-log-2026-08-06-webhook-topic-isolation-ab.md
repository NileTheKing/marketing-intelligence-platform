# Webhook Topic Isolation Fault-Injection A/B

Status: active evidence

Date: 2026-08-06

External smoke verification: 2026-08-07

## Question

Does synchronous external Webhook work on the same Kafka topic and consumer execution path delay
the next internal FCFS command and its offset progress?

## Environment

- local Spring Kafka embedded KRaft broker
- one partition per experiment topic
- `max.poll.records=20`, `AckMode.BATCH`, auto commit disabled
- deterministic Webhook blocking delay: 2,000 ms
- three repetitions in one test execution
- test artifact:
  `core-service/src/test/java/com/axon/core_service/commandprocessing/WebhookTopicIsolationExperiment.java`

This is a causal topology experiment. It reconstructs the previous shared-topic execution boundary
in a focused Kafka test harness; it is not an OCI capacity test or a real external-provider test.

## Scenario

Before:

```text
WEBHOOK -> shared topic / shared consumer -> 2s external wait
FCFS    -> same topic / same consumer
```

The FCFS record is published after the Webhook listener starts waiting. The experiment records FCFS
completion time and the shared group's committed offset during that wait.

After:

```text
WEBHOOK -> webhook topic / webhook consumer -> 2s external wait
FCFS    -> internal topic / internal consumer
```

The FCFS record is again published only after the Webhook listener starts waiting. The experiment
checks whether the internal group can complete and commit while the Webhook group is still blocked.

## Results

| Run | Shared topic: FCFS completion | Shared offset during Webhook | Isolated: FCFS completion | Internal offset | Webhook offset during delay |
|---|---:|---|---:|---|---|
| 1 | 1,920 ms | baseline `0` → `0` | 5 ms | baseline `0` → `1` | baseline `0` → `0` |
| 2 | 2,013 ms | baseline `2` → `2` | 4 ms | baseline `1` → `2` | baseline `1` → `1` |
| 3 | 2,015 ms | baseline `4` → `4` | 4 ms | baseline `2` → `3` | baseline `2` → `2` |

- Shared topic mean FCFS completion: about 1,983 ms.
- Isolated topic mean FCFS completion: about 4.3 ms.
- In every shared-topic run, the committed offset did not advance while Webhook was waiting.
- In every isolated run, the internal group committed one FCFS record while the Webhook group's
  offset remained at its baseline and Webhook execution was still in progress.

## Decision

The result confirms head-of-line blocking at the shared Kafka consumer responsibility boundary.
Webhook commands now use `axon.webhook.command` and group `axon-webhook-group`. FCFS and coupon
commands remain on `axon.campaign-activity.command` and group `axon-group`.

The legacy dispatcher forwards an old `WEBHOOK` command from the shared topic to the isolated topic
without executing external HTTP.

## Claim Boundary

Supported:

- A controlled 2-second Webhook delay blocked the next FCFS command and offset progress on one shared
  consumer.
- Topic and consumer-group isolation allowed FCFS to complete and commit while Webhook was still
  blocked.
- `HttpWebhookClientExternalSmokeTest` sent one synthetic JSON request from Java 21 to a public
  Webhook.site endpoint. The receiver confirmed the `application/json` body and
  `Idempotency-Key: webhook:smoke:20260807` header.

Not supported:

- a production latency or throughput improvement percentage
- an OCI mixed-load result
- a real CRM, Kakao, email, or other provider-specific integration
- end-to-end exactly-once Webhook delivery
