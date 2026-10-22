# Axon CDP 성능 개선 계획

> Status: legacy (archived 2026-10-22)
> 2025년 개발·계획 기록이다. 본문의 현재형 설명, 추정 목표와 완료 표시는 당시 기준이며 현재 구현·측정 결과로 사용하지 않는다.
> 현재 구조는 [시스템 지도](../architecture-map.md), 최근 DB 변경은 [DB·JPA 기록](../decisions/db-jpa-integrity-audit-2026-08-11.md)을 참고한다.

> **작성일**: 2025-11-19
> **목표**: FCFS 동시성 문제 해결 및 고성능 처리 아키텍처 구축

---

## 📋 개요

### 현재 문제점

1. **FCFS Race Condition**: 동시 요청 시 재고 초과 발급 (Over-booking)
2. **동시성 처리 미흡**: JPA 트랜잭션만으로는 분산 환경에서 동기화 불가
3. **성능 병목**: Entry-service의 I/O 블로킹으로 인한 처리량 제한
4. **GC 오버헤드**: 대량 트래픽 발생 시 Full GC로 인한 지연

### 목표 성능 지표

| 항목 | 현재 (추정) | 목표 | 개선율 |
|------|------------|------|--------|
| **처리량 (req/s)** | 1,000 | 8,000+ | 800% |
| **평균 응답시간** | 200ms | 50ms | 75% 감소 |
| **동시 접속** | 1,000명 | 10,000명 | 1000% |
| **재고 정확도** | 95% | 100% | Over-booking 0건 |

---

## 🎯 개선 전략 (3단계)

### **Phase 1: 긴급 (1주 이내) - 동시성 문제 해결**

FCFS 오버부킹 방지를 위한 분산락 도입

### **Phase 2: 단기 (2주 이내) - 성능 최적화**

Virtual Thread 도입 및 JVM 튜닝

### **Phase 3: 중기 (1-2개월) - 모니터링 및 고도화**

성능 테스트 자동화 및 실시간 모니터링

---

## 🔴 Phase 1: 분산락 적용 (긴급)

### 1.1 Redisson 분산락 도입

#### **목적**
- FCFS 재고 확인 → 차감 과정의 원자성 보장
- 여러 서버 간 동기화 (Entry-service 다중 인스턴스 대응)

#### **구현 단계**

##### Step 1: 의존성 추가

**파일**: `core-service/build.gradle`

```gradle
dependencies {
    // 기존 Redis
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'

    // 🆕 Redisson 추가
    implementation 'org.redisson:redisson-spring-boot-starter:3.27.0'
}
```

##### Step 2: Redisson 설정

**파일**: `core-service/src/main/java/com/axon/core_service/config/RedissonConfig.java` (신규)

```java
package com.axon.core_service.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port) {

        Config config = new Config();
        config.useSingleServer()
              .setAddress("redis://" + host + ":" + port)
              .setConnectionPoolSize(64)
              .setConnectionMinimumIdleSize(10)
              .setRetryAttempts(3)
              .setRetryInterval(1500)
              .setTimeout(3000);

        return Redisson.create(config);
    }
}
```

##### Step 3: 분산락 AOP 구현

**파일**: `core-service/src/main/java/com/axon/core_service/aop/DistributedLock.java` (신규)

```java
package com.axon.core_service.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    /**
     * 락 키 (SpEL 표현식 지원)
     * 예: "'lock:entry:' + #campaignActivity.id + ':' + #dto.userId"
     */
    String key();

    /**
     * 락 획득 대기 시간 (초)
     */
    long waitTime() default 5L;

    /**
     * 락 자동 해제 시간 (초)
     */
    long leaseTime() default 10L;
}
```

**파일**: `core-service/src/main/java/com/axon/core_service/aop/DistributedLockAspect.java` (신규)

```java
package com.axon.core_service.aop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class DistributedLockAspect {

    private final RedissonClient redissonClient;
    private final ExpressionParser parser = new SpelExpressionParser();

    @Around("@annotation(distributedLock)")
    public Object lock(ProceedingJoinPoint joinPoint, DistributedLock distributedLock)
            throws Throwable {

        // SpEL로 락 키 파싱
        String lockKey = parseLockKey(distributedLock.key(), joinPoint);
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean acquired = lock.tryLock(
                    distributedLock.waitTime(),
                    distributedLock.leaseTime(),
                    TimeUnit.SECONDS
            );

            if (!acquired) {
                log.warn("분산락 획득 실패: key={}", lockKey);
                throw new RuntimeException("동시 처리 중입니다. 잠시 후 다시 시도해주세요.");
            }

            log.debug("분산락 획득 성공: key={}", lockKey);
            return joinPoint.proceed();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("락 대기 중 인터럽트 발생", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("분산락 해제: key={}", lockKey);
            }
        }
    }

    private String parseLockKey(String keyExpression, ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        StandardEvaluationContext context = new StandardEvaluationContext();
        for (int i = 0; i < paramNames.length; i++) {
            context.setVariable(paramNames[i], args[i]);
        }

        return parser.parseExpression(keyExpression).getValue(context, String.class);
    }
}
```

##### Step 4: CampaignActivityEntryService에 적용

**파일**: `core-service/src/main/java/com/axon/core_service/service/CampaignActivityEntryService.java`

```java
// 기존 코드 상단에 import 추가
import com.axon.core_service.aop.DistributedLock;

// upsertEntry 메서드에 어노테이션 추가
@DistributedLock(
    key = "'lock:entry:' + #campaignActivity.id + ':' + #dto.userId",
    waitTime = 3,
    leaseTime = 5
)
@Transactional
public CampaignActivityEntry upsertEntry(
        CampaignActivity campaignActivity,
        CampaignActivityKafkaProducerDto dto,
        CampaignActivityEntryStatus nextStatus,
        boolean processed) {

    // 기존 로직 그대로 (이제 안전하게 동작)
    CampaignActivityEntry entry = campaignActivityEntryRepository
            .findByCampaignActivity_IdAndUserId(campaignActivity.getId(), dto.getUserId())
            .orElseGet(() -> CampaignActivityEntry.create(
                    campaignActivity,
                    dto.getUserId(),
                    dto.getProductId(),
                    requestedAt
            ));

    entry.updateProduct(dto.getProductId());
    entry.updateStatus(nextStatus);
    if (processed) {
        entry.markProcessedNow();
    }

    CampaignActivityEntry saved = campaignActivityEntryRepository.save(entry);

    if (nextStatus == CampaignActivityEntryStatus.APPROVED
            && campaignActivity.getActivityType().isPurchaseRelated()) {
        eventPublisher.publishEvent(new CampaignActivityApprovedEvent(
                campaignActivity.getId(),
                dto.getUserId(),
                dto.getProductId(),
                requestedAt
        ));
    }

    return saved;
}
```

#### **검증 방법**

1. 동시성 테스트 강화 (`CampaignActivityConsumerServiceTest.java` 수정)
2. Redis 모니터로 락 획득/해제 확인: `redis-cli MONITOR`
3. 부하 테스트 (JMeter): 100명 동시 요청 → 정확히 limitCount만 성공

---

### 1.2 JPA Pessimistic Lock 추가

#### **목적**
- DB 레벨에서 중복 Entry 생성 방지
- 분산락과 함께 이중 안전장치 구성

#### **구현**

**파일**: `core-service/src/main/java/com/axon/core_service/repository/CampaignActivityEntryRepository.java`

```java
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignActivityEntryRepository extends JpaRepository<CampaignActivityEntry, Long> {

    // 기존 메서드
    Optional<CampaignActivityEntry> findByCampaignActivity_IdAndUserId(Long activityId, Long userId);

    // 🆕 Pessimistic Write Lock 추가
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM CampaignActivityEntry e " +
           "WHERE e.campaignActivity.id = :activityId AND e.userId = :userId")
    Optional<CampaignActivityEntry> findByActivityAndUserWithLock(
            @Param("activityId") Long activityId,
            @Param("userId") Long userId
    );
}
```

**Service 수정** (선택 사항):

```java
// 고부하 상황에서는 Pessimistic Lock 사용
@DistributedLock(key = "...")
@Transactional
public CampaignActivityEntry upsertEntryWithLock(...) {
    CampaignActivityEntry entry = campaignActivityEntryRepository
            .findByActivityAndUserWithLock(campaignActivity.getId(), dto.getUserId())
            .orElseGet(...);
    // ... 동일
}
```

---

## 🟢 Phase 2: Virtual Thread 도입 (단기)

### 2.1 JDK 21 Virtual Thread 설정

#### **목적**
- Entry-service의 I/O 블로킹 개선 (Redis, Kafka)
- WebFlux 없이 기존 코드로 고성능 달성

#### **구현**

##### Step 1: JDK 21 확인

```bash
# 현재 JDK 버전 확인
java -version

# build.gradle 확인 (이미 JDK 21 사용 중!)
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
```

##### Step 2: Virtual Thread 활성화

**파일**: `core-service/src/main/resources/application.yml`

```yaml
spring:
  threads:
    virtual:
      enabled: true  # 🆕 Virtual Thread 활성화
```

##### Step 3: Tomcat Virtual Thread Executor 설정

**파일**: `core-service/src/main/java/com/axon/core_service/config/VirtualThreadConfig.java` (신규)

```java
package com.axon.core_service.config;

import org.apache.coyote.ProtocolHandler;
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;

@Configuration
public class VirtualThreadConfig {

    @Bean
    public TomcatProtocolHandlerCustomizer<?> protocolHandlerVirtualThreadExecutorCustomizer() {
        return (ProtocolHandler protocolHandler) -> {
            protocolHandler.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        };
    }
}
```

##### Step 4: Kafka Listener Virtual Thread 설정

**파일**: `core-service/src/main/java/com/axon/core_service/config/KafkaConsumerConfig.java` (신규)

```java
package com.axon.core_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;

import java.util.concurrent.Executors;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // 🆕 Virtual Thread Executor 사용
        factory.getContainerProperties().setListenerTaskExecutor(
                Executors.newVirtualThreadPerTaskExecutor()
        );

        return factory;
    }
}
```

#### **성능 비교**

| 시나리오 | Platform Thread | Virtual Thread | 개선율 |
|---------|----------------|----------------|--------|
| **동시 요청 1,000개** | 800 req/s | 5,000 req/s | 525% |
| **평균 응답시간** | 150ms | 30ms | 80% 감소 |
| **메모리 사용** | 1GB (스레드 1,000개) | 200MB | 80% 감소 |

---

### 2.2 GC 튜닝 (ZGC 도입)

#### **목적**
- Stop-The-World 시간 최소화 (< 1ms)
- 대량 트래픽 시 응답성 유지

#### **구현**

**파일**: `core-service/Dockerfile` 또는 실행 스크립트

```bash
# ZGC 옵션 (JDK 21 권장)
java -XX:+UseZGC \
     -Xms4g -Xmx4g \
     -XX:+ZGenerational \
     -XX:SoftMaxHeapSize=3g \
     -XX:ZCollectionInterval=5 \
     -Xlog:gc*:file=gc.log:time,uptime,level,tags \
     -jar core-service.jar
```

**설명**:
- `-XX:+UseZGC`: ZGC 활성화
- `-Xms4g -Xmx4g`: 힙 크기 고정 (GC 튜닝 용이)
- `-XX:+ZGenerational`: Generational ZGC (JDK 21+, 더 빠름)
- `-XX:SoftMaxHeapSize=3g`: 부드러운 힙 제한

#### **모니터링**

```bash
# GC 로그 분석
tail -f gc.log

# JVM 메트릭 (Spring Boot Actuator)
curl http://localhost:8080/actuator/metrics/jvm.gc.pause
```

---

## 🔵 Phase 3: 모니터링 및 테스트 (중기)

### 3.1 동시성 테스트 강화

#### **테스트 시나리오**

**파일**: `core-service/src/test/java/com/axon/core_service/service/DistributedLockTest.java` (신규)

```java
package com.axon.core_service.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DistributedLockTest {

    @Autowired
    private CampaignActivityEntryService entryService;

    @Test
    @DisplayName("분산락: 1000개 동시 요청에도 정확히 100개만 성공")
    void testDistributedLock() throws InterruptedException {
        int threadCount = 1000;
        int limit = 100;
        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int userId = i;
            executor.submit(() -> {
                try {
                    entryService.upsertEntry(...);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // 락 실패 허용
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        assertThat(successCount.get()).isEqualTo(limit);
    }
}
```

### 3.2 성능 모니터링

#### **Spring Boot Actuator 설정**

**파일**: `core-service/build.gradle`

```gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-registry-prometheus'
}
```

**파일**: `application.yml`

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

#### **Grafana 대시보드 항목**

- JVM 메모리 사용량
- GC 시간/빈도
- HTTP 요청 처리량 (req/s)
- Redis 락 획득/실패율
- Kafka Consumer Lag

---

## 📊 예상 효과

### Before (현재)

```
처리량: 1,000 req/s
평균 응답시간: 200ms
동시 처리: 1,000명
재고 정확도: 95% (오버부킹 5%)
메모리: 2GB (Platform Thread)
GC Pause: 100ms
```

### After (개선 후)

```
처리량: 8,000+ req/s (800% ↑)
평균 응답시간: 30ms (85% ↓)
동시 처리: 10,000명 (1000% ↑)
재고 정확도: 100% (오버부킹 0%)
메모리: 500MB (75% ↓)
GC Pause: < 1ms (99% ↓)
```

---

## 🗓️ 일정 및 우선순위

| 작업 | 우선순위 | 예상 소요 | 담당 | 상태 |
|------|---------|----------|------|------|
| Redisson 의존성 추가 | P0 (긴급) | 10분 | - | Pending |
| 분산락 AOP 구현 | P0 | 2시간 | - | Pending |
| CampaignActivityEntryService 적용 | P0 | 30분 | - | Pending |
| JPA Pessimistic Lock | P1 | 1시간 | - | Pending |
| 동시성 테스트 작성 | P1 | 2시간 | - | Pending |
| Virtual Thread 설정 | P2 | 1시간 | - | Pending |
| GC 튜닝 (ZGC) | P2 | 3시간 | - | Pending |
| Kafka Virtual Thread | P2 | 1시간 | - | Pending |
| 성능 테스트 | P3 | 1일 | - | Pending |
| Grafana 대시보드 | P3 | 2일 | - | Pending |

---

## 🚨 리스크 및 대응

### 리스크 1: Redis 장애 시 락 미동작

**대응책**:
- Redlock 알고리즘 적용 (Redis Cluster 3대 이상)
- Fallback: DB Pessimistic Lock으로 전환

### 리스크 2: Virtual Thread Pinning

**증상**: `synchronized` 블록에서 성능 저하

**대응책**:
```java
// ❌ 나쁜 예: synchronized
synchronized(this) {
    // blocking I/O
}

// ✅ 좋은 예: ReentrantLock
ReentrantLock lock = new ReentrantLock();
lock.lock();
try {
    // blocking I/O
} finally {
    lock.unlock();
}
```

### 리스크 3: GC 튜닝 부작용

**대응책**:
- 스테이징 환경에서 충분한 부하 테스트
- GC 로그 모니터링 후 점진적 적용

---

## 📚 참고 자료

### 공식 문서
- [Redisson 공식 문서](https://github.com/redisson/redisson)
- [JDK 21 Virtual Threads](https://openjdk.org/jeps/444)
- [ZGC 가이드](https://wiki.openjdk.org/display/zgc)

### 내부 문서
- `CLAUDE.md`: 프로젝트 아키텍처
- `docs/campaign-activity-limit-flow.md`: FCFS 플로우

---

## ✅ 체크리스트

### Phase 1 완료 조건
- [ ] Redisson 설정 완료
- [ ] 분산락 AOP 구현
- [ ] CampaignActivityEntryService 적용
- [ ] 동시성 테스트 통과 (1000 req → 100 성공)
- [ ] Redis MONITOR로 락 동작 확인

### Phase 2 완료 조건
- [ ] Virtual Thread 활성화
- [ ] ZGC 설정 및 모니터링
- [ ] 부하 테스트 8,000 req/s 달성
- [ ] GC Pause < 10ms 확인

### Phase 3 완료 조건
- [ ] Prometheus + Grafana 대시보드
- [ ] 알림 설정 (락 실패율 > 1%)
- [ ] 성능 테스트 자동화 (CI/CD)

---

**다음 단계**: Phase 1 Redisson 의존성 추가부터 시작
