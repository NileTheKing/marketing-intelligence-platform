# 📊 Dashboard & Performance Test Scripts

이 디렉토리는 Axon 플랫폼의 비즈니스 로직 검증 및 대규모 트래픽 안정성 테스트를 위한 스크립트를 포함하고 있습니다.

## 📈 부하 테스트 및 안정성 검증 (Performance & Stability)

대규모 트래픽 상황에서 시스템이 데이터 정합성을 유지하며 안정적으로 동작하는지 k6를 통해 검증했습니다.

### 1. 테스트 시나리오: 3,000 VU Spike Test
*   **목표**: 순간적인 접속자 폭주(Spike) 상황에서의 오버부킹 방지 및 가용성 확보.
*   **수행**: 0에서 3,000 VU(가상 사용자)까지 급격히 부하를 높여 시스템 임계점 측정.

### 2. 핵심 검증 결과 (Stability Metrics)
*   **오버부킹 Zero (Perfect Integrity)**: Redis `act-check-then` 원자적 연산을 통해 수만 건의 동시 요청 속에서도 단 1건의 초과 예약 없이 정확한 수량 제어 성공.
*   **시스템 가용성 99.98% 달성**: 총 2.2만 건 이상의 요청 중 순수 시스템 에러(500)는 단 5건(0.02%)에 불과. (나머지 31% 실패는 재고 소진 및 중복 방지에 따른 정상 비즈니스 응답으로 확인)
*   **동시성 이슈 해결**: 분산 환경에서 다수 노드가 접근함에도 데이터 경합(Race Condition) 및 데이터 유실 발생 0건.

### 3. 주요 성능 최적화 사례
*   **TCP 커널 파라미터 튜닝**: 초기 300 VU 이상에서 발생하던 `Connection Reset` 해결을 위해 Ingress 및 OS 레벨의 **TCP SYN Queue** 증설.
*   **지연 재고 동기화 (Deferred Sync)**: DB Row Lock 경합을 제거하기 위해 실시간 재고 차감을 지연시키고, 이벤트 종료 후 스케줄러가 사후 정산하는 **결과적 일관성 모델** 도입.
*   **데이터 적재 성능 극대화**: **Micro-Batching**과 **JDBC Bulk Insert**를 통해 DB 쓰기 부하를 분산하고 적재 속도 향상.

---

## 🚀 테스트 퀵 스타트

### 필수 환경 설정
> ⚠️ **보안 공지**: 모든 로컬 테스트 비밀번호는 `axon1234`로 통일되어 있습니다.

```bash
export DB_HOST=127.0.0.1
export DB_PORT=3306
export DB_USER=axon_user
export DB_PASS=axon1234
export DB_NAME=axon_db
export REDIS_PASSWORD=axon1234
```

### 완전 자동화 테스트 (`run-dashboard-test.sh`)
```bash
./run-dashboard-test.sh [activityId] [numVisitors]
```

---

## 🔍 데이터 검증 및 디버깅

### 1. 정합성 체크 (MySQL)
실시간 응모 성공(`entries`)과 최종 구매 기록(`purchases`)이 일치하는지 확인합니다.
```bash
mysql -u axon_user -paxon1234 axon_db -e "
  SELECT 
    (SELECT COUNT(*) FROM campaign_activity_entries WHERE campaign_activity_id = 1) as entries,
    (SELECT COUNT(*) FROM purchases WHERE campaign_activity_id = 1) as purchases;
"
```

### 2. 재고 동기화 확인
테스트 종료 후 아래 스케줄러 로직이 정상 작동하여 `products` 테이블의 재고가 차감되었는지 확인해야 합니다. (지연 처리 설계에 의함)

---

## 📚 참고 문서
- **Performance Plan**: `docs/performance-improvement-plan.md`
- **Architecture**: `docs/purchase-event-flow.md`
