# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Axon CDP is a multi-module Spring Boot ecosystem implementing real-time campaign intelligence with FCFS (First Come First Serve) promotions. The architecture separates concerns between an entry validation layer and a domain processing core, connected via Kafka messaging.

### Key Architecture Components

**Entry-service (port 8081)**: Lightweight validation and reservation service
- Handles FCFS slot reservations via Redis atomic counters and Redisson distributed locks
- Publishes events to Kafka topics: `axon.event.raw`, `axon.campaign-activity.command`
- Implements fast validation using Redis-cached user data; calls Core-service for heavy validation
- Issues reservation tokens for payment flows; **Virtual Threads enabled** (`spring.threads.virtual.enabled=true`)

**Core-service (port 8080)**: Domain logic and persistence engine
- Consumes Kafka commands via micro-batch buffer (20-msg threshold + 100ms flush via `ConcurrentLinkedQueue`)
- Manages MySQL persistence for campaigns, entries, users, products
- Handles complex validation rules and user summary updates
- Serves Thymeleaf UI with auto-injected behavior tracking
- Exposes LLM-powered natural language dashboard queries (Gemini API, `gemini` profile)

**common-messaging**: Shared DTOs and Kafka topic constants used by both services
- Key DTOs: `CampaignActivityKafkaProducerDto`, `UserBehaviorEventMessage`, `ReservationTokenPayload`, `UserCacheDto`
- Topic constants centralized in `KafkaTopics`

**Data Flow**: `Browser (JS tracker) → Entry-service (Redis FCFS + Virtual Threads) → Kafka → Core-service (micro-batch consumer, MySQL) + Kafka Connect → Elasticsearch`

### Critical Kafka Topics
- `axon.event.raw`: Raw behavior events from JS tracker and backend synthetic events
- `axon.campaign-activity.command`: FCFS reservation commands
- `axon.campaign-activity.log`: Domain event logs
- `axon.user.login`: User authentication events

## Development Commands

### Infrastructure Setup
```bash
# Start all services (Kafka, MySQL, Redis, Elasticsearch, Kafka Connect)
docker-compose up -d

# Check service status
docker-compose ps
```

### Build & Test Commands
```bash
# Build all modules
./gradlew build

# Module-specific builds (each service has its own gradlew)
cd core-service && ./gradlew build --no-daemon
cd entry-service && ./gradlew build --no-daemon

# Run specific tests
cd core-service && ./gradlew test --tests="CampaignActivityConsumerServiceTest"
cd entry-service && ./gradlew test --tests="EntryControllerTest"

# Run services locally
cd core-service && ./gradlew bootRun
cd entry-service && ./gradlew bootRun
```

### Debugging Infrastructure
```bash
# Check database tables
docker exec axon-mysql mysql -u axon_user -paxon_password axon_db -e "SHOW TABLES;"

# Redis connection test
redis-cli -h localhost -p 6379 ping

# Elasticsearch health
curl http://localhost:9200/_cluster/health

# Kafka Connect status
curl http://localhost:8083/connectors
```

## Key Implementation Details

### Redis FCFS + Distributed Lock
- Participant tracking: Redis Sets with key pattern `campaignActivity:{id}:participants`
- Counter management: Redis counters for order determination
- **Distributed lock**: Redisson-backed `@DistributedLock` AOP annotation (`DistributedLockAspect`) ensures atomic FCFS across multiple Entry-service instances — prevents over-booking
- Reservation tokens: Temporary reservations with TTL, validated during payment confirm step

### Micro-Batch Consumer Pattern
`CampaignActivityConsumerService` buffers incoming Kafka messages in a `ConcurrentLinkedQueue` and flushes to `CampaignStrategy` implementations either when 20 messages accumulate or every 100ms via `@Scheduled`. This smooths DB write load without requiring Kafka batch listener configuration.

### Campaign Strategy Pattern
Campaign processing uses Strategy Pattern: `CampaignStrategy` interface with implementations `FirstComeFirstServeStrategy`, `CouponStrategy`. The consumer builds an unmodifiable `Map<CampaignActivityType, CampaignStrategy>` at startup (Spring auto-injects all strategy beans). Adding a new campaign type = new `CampaignStrategy` implementation only.

### LLM Dashboard Queries (Gemini)
- `GeminiLLMQueryService` implements `LLMQueryService` and is active on `gemini` or `prod` profiles
- Accepts natural language queries, fetches live dashboard data, sends to `gemini-2.5-flash-lite` API
- `MockLLMQueryService` is the fallback on other profiles
- Requires `GEMINI_API_KEY` environment variable

### Kafka Serialization
- Producer: `JsonSerializer` with type headers enabled
- Consumer: `JsonDeserializer` with trusted packages set to `"*"`
- Shared DTOs defined in `common-messaging` module

### Database Configuration
- **Core-service**: MySQL on localhost:3306, database `axon_db`; HikariCP pool size 20
- **Both services**: Redis on localhost:6379 for caching and FCFS logic
- **Hibernate**: `ddl-auto: update` in development (change to `validate` for production)
- All credentials use env vars: `DB_USERNAME`, `DB_PASSWORD`, `REDIS_PASSWORD`, `JWT_SECRET`, `PAYMENT_TOKEN_SECRET`, `GEMINI_API_KEY`

### Active Spring Profiles
- Core-service: `oauth`, `gemini` (see `spring.profiles.include` in application.yml)
- Entry-service: `oauth`
- Gemini LLM beans only load under `gemini` or `prod` profile

### Schedulers (Core-service)
- `CohortLtvBatchScheduler`: runs cohort LTV batch via `CohortLtvBatchService`
- `PaymentRecoveryScheduler`: retries failed payments
- `UserPurchaseScheduler`: syncs user purchase summaries
- `CampaignStockSyncScheduler`: syncs campaign stock

## Critical Integration Points

### Behavior Tracker JavaScript
- Auto-injected into Thymeleaf templates from `core-service/src/main/resources/static/js/behavior-tracker.js`
- Sends events to Entry-service `/api/v1/behavior/events` endpoint
- Configuration via `axon-tracker-config.js` for token/user providers
- Backend also publishes synthetic purchase events to `axon.event.raw` with a unified schema (synthetic URL: `/campaign-activity/{id}/purchase`)

### Kafka Connect Pipeline
- Elasticsearch Sink connectors: `elasticsearch-sink-behavior-events`, `elasticsearch-sink-connector`
- Maps Kafka topics to ES indices: `behavior-events`, `axon.event.raw`
- Handles schema-less JSON with automatic index creation

### Payment Flow
1. Entry-service validates FCFS slot → issues reservation token (Redis TTL)
2. Client calls payment confirm → Entry-service validates token → publishes `CampaignActivityKafkaProducerDto` to Kafka
3. Core-service consumer processes → writes `CampaignActivityEntry` to MySQL

### Service Communication
- Entry-service → Core-service: Via Kafka asynchronous messaging only (no direct HTTP for business logic)
- Entry-service → Core-service: HTTP (`CoreValidationService`) for heavy user validation cache miss
- JWT tokens shared between services (secret: `JWT_SECRET` env var)
- OAuth2 integration for user authentication (profile: `oauth`)

### Validation Strategy (Campaign Limits)
`DynamicValidationService` uses `ValidationLimitFactoryService` to load filter rules per campaign activity and delegates to `ValidationLimitStrategy` implementations (e.g., `RecentPurchaseLimit`). Filter config stored as JSON in `FilterDetail` (JPA attribute converter).

## Testing Notes

- Integration tests require Docker Compose stack to be running
- Known flaky test: `CampaignActivityConsumerServiceTest` concurrency test may fail due to race conditions
- Tests use `@SpringBootTest` with test-specific Redis/MySQL cleanup
- Mock external dependencies in unit tests; use TestContainers for integration tests if needed
- LLM tests: use `MockLLMQueryService` (default profile) to avoid Gemini API calls in CI

## Documentation References

- Architecture / Engineering rationale: `docs/PORTFOLIO_MASTER.md`
- Performance improvement plan: `core-service/docs/performance-improvement-plan.md`
- FCFS refactor plan: `docs/FCFS_Refactor.md`
- Project task board: `docs/project-tasks.md`
- Architecture flows: `docs/flow/` (purchase-event, campaign-activity-limit)
- Behavior tracking: `docs/behavior-tracker.md`
- Analytics pipeline: `docs/behavior-event-fluentd-plan.md`
- Dashboard specifications: `docs/marketing-dashboard-spec.md`
- Filter system: `docs/Filter_System_Architecture.md`
- Payment resilience: `docs/payment-resilience-architecture.md`
