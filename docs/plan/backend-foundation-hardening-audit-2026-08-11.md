# 백엔드 기본기 강화 감사 — 2026-08-11

상태: active

## 결론

새 기능을 더하기 전에 **실행 정합성 → 테스트 신뢰성 → 보안·운영 경계 → 구조 복잡성** 순서로 점검했다. 스타일이 마음에 들지 않는 코드는 건드리지 않고, 실제 오동작 가능성이 있거나 현재 실행 경로를 흐리는 코드만 수정했다.

## 변경 전 → 문제 → 변경 후

### 1. 비밀값과 API 경계

- **변경 전:** JWT 서명 키와 예약·결제 토큰 일부가 로그에 남았고, Naver OAuth 자격증명이 설정 파일에 포함돼 있었다. Entry의 쿠폰 API와 Core 모니터링 프록시에는 인증이 없었으며, Entry 컨트롤러 하나가 전역 CORS와 별개로 모든 출처를 허용했다.
- **문제:** 로그·저장소를 통한 비밀값 노출과 인증 우회 가능성이 있었다.
- **변경 후:** 민감 로그를 제거하고 OAuth 값을 환경변수화했다. 누락된 인증을 추가하고 CORS 정책을 전역 설정으로 통일했다.

### 2. 외부 호출 실패 경계

- **변경 전:** Entry→Core와 Core→Gemini 호출에 명시적인 연결·응답 제한 시간이 없었다.
- **문제:** 상대 서비스나 네트워크가 느릴 때 요청 스레드가 예상보다 오래 점유될 수 있었다.
- **변경 후:** 두 HTTP 클라이언트에 connect/read timeout을 설정했다.

### 3. Kafka 성공·실패 판정

- **변경 전:** 쿠폰 발급은 Kafka 전송을 요청한 직후 성공을 반환했다. 지원하지 않거나 type이 없는 캠페인 명령은 로그만 남긴 뒤 offset이 commit될 수 있었다.
- **문제:** broker가 메시지를 받지 못했는데 API는 성공할 수 있었고, 처리하지 못한 명령은 재처리 근거 없이 사라질 수 있었다.
- **변경 후:** 쿠폰은 broker ACK 뒤 성공을 반환한다. 미지원 명령은 command DLT에 기록하고, DLT 전송까지 실패하면 offset commit을 막는다.

### 4. 트랜잭션과 분산락

- **변경 전:** Cohort의 액티비티별 `@Transactional` 메서드는 같은 클래스 내부에서 호출돼 의도한 새 경계가 만들어지지 않았다. RFM·역방향 대사는 트랜잭션 commit보다 분산락이 먼저 해제될 수 있었다.
- **문제:** 한 액티비티의 실패 범위가 예상보다 커질 수 있었고, 다른 인스턴스가 아직 commit되지 않은 상태를 기준으로 다음 작업을 시작할 수 있었다.
- **변경 후:** Cohort는 `TransactionTemplate`으로 액티비티별 경계를 실제로 만든다. RFM·대사는 분산락 안에서 commit까지 끝낸 뒤 락을 해제한다.

### 5. 집계 기준의 일관성

- **변경 전:** Cohort SQL이 취소·환불 구매와 종료 경계 구매를 포함했고 실행 중 현재 시각을 다시 읽었다. Dashboard CUSTOM 기간과 행동 이벤트 시간대·종료 경계도 호출·실행 환경에 따라 다르게 해석될 수 있었다.
- **문제:** 같은 데이터도 실행 시각이나 서버 시간대에 따라 집계 결과가 달라질 수 있었다.
- **변경 후:** 유효 구매만 집계하고 시간 범위를 `[start, end)`로 통일했다. 전달받은 기준 시각과 `Asia/Seoul` 업무 시간대를 명시적으로 사용한다.

### 6. 검증과 조회 경계

- **변경 전:** HEAVY 캠페인의 여러 조건을 모두 AND로 확인하지 않았고, 조건이 없으면 오류로 판정했다. 비트랜잭션 스케줄러는 LAZY 연관관계에 접근했다.
- **문제:** 조건 일부만 만족한 사용자가 통과하거나, 정상 캠페인이 실패할 수 있었다. 스케줄러에서는 `LazyInitializationException` 가능성도 있었다.
- **변경 후:** 모든 HEAVY 조건을 AND로 평가하고 조건이 없으면 통과시킨다. 필요한 연관관계는 repository에서 EntityGraph로 함께 조회한다.

### 7. 예약의 핵심 결과와 부가 작업

- **변경 전:** Redis 예약은 성공했어도 부가 행동 로그 executor가 작업 접수를 거부하면 API 결과가 실패로 바뀌었다. 예약 만료 처리는 토큰 생성과 다른 Base64 decoder를 사용했다.
- **문제:** 이미 성공한 예약을 사용자가 실패로 인식해 재시도할 수 있었고, 정상 토큰의 만료 처리가 실패할 수 있었다.
- **변경 후:** `TaskRejectedException`은 부가 로그 실패로만 격리하고 예약 성공은 유지한다. 토큰 생성·해석 형식을 맞추고 HMAC은 상수 시간으로 비교한다.

### 8. 경쟁 구현과 잘못된 테스트

- **변경 전:** 호출되지 않는 과거 구매 이벤트·Redisson AOP 락·Gemini prompt 경로와 placeholder가 현재 코드와 함께 남아 있었다. 일부 성능 테스트는 insert 실패를 삼킨 채 처리 시간을 비교했다.
- **문제:** 실제 실행 경로를 판단하기 어려웠고, 실패한 작업을 빠른 성공처럼 해석할 수 있었다.
- **변경 후:** production에서 참조되지 않는 과거 경로와 거짓 신뢰를 주는 테스트를 제거했다. 현재 FCFS 원장 처리, 스케줄러 락, Gemini Function Calling 경로는 유지했다.

### 9. API 테스트와 CI 통합 테스트 신뢰성

- **변경 전:** controller 테스트 상당수가 Security·Bean Validation·전역 예외 처리를 우회했다. Testcontainers는 Docker가 없으면 suite 전체가 skip돼도 CI 명령 자체는 성공할 수 있었다.
- **문제:** 미인증 Entry가 403을 반환하거나 관리 API의 예상 가능한 실패가 500으로 새는 계약 오류를 기존 테스트가 발견하지 못했다. CI 환경 이상으로 핵심 MySQL·Redis 검증이 실행되지 않아도 성공으로 오인할 수 있었다.
- **변경 후:** Entry·BehaviorEvent·CampaignActivity·Event·Coupon의 대표 계약을 실제 MVC 필터 체인으로 검증한다. CI는 FCFS Kafka→MySQL, CampaignActivity HTTP→MySQL, Entry Redis suite의 JUnit XML이 없거나 skip되면 실패한다.

## 검증

- Core: 184 tests, failure 0, error 0, skip 25
- Entry: 47 tests, failure 0, error 0, skip 3
- `git diff --check` 통과
- `docker compose -f compose.app.yml config --quiet` 통과

로컬 환경에는 Docker가 없어 Testcontainers 테스트가 명시적으로 skip됐다. 이번 변경부터 GitHub Actions CI는 핵심 suite가 실제 실행되지 않으면 별도 검사 단계에서 실패한다.

2026-08-12 최종 GitHub Actions CI에서 Core·Entry 전체 테스트, FCFS Kafka→MySQL·CampaignActivity HTTP→MySQL·Entry Redis 필수 suite의 skip 방지 검사, Docker Compose 설정 검증, Core·Entry 이미지 빌드까지 모두 통과했다. 이 과정에서 전체 context에 섞인 보안 테스트용 probe와 테스트 클래스별 Testcontainers 종료 문제도 수정했다. 검증 실행은 [CI run 31561058964](https://github.com/NileTheKing/marketing-intelligence-platform/actions/runs/31561058964)이다.

## 남은 작업

| 우선순위 | 작업 | 현재 보류한 이유 |
| --- | --- | --- |
| 필수 | 과거 Git 이력에 노출된 Naver OAuth 자격증명 재발급 | 파일 수정만으로 기존 키가 무효화되지는 않음 |
| 높음 | SHOP 결제 정합성·신뢰 경계 재설계 | 다른 팀원 담당 범위이며 callback·가격 권위·트랜잭션·멱등키 합의 필요 |
| 중간 | Flyway/Liquibase 도입 후 `ddl-auto: validate` 전환 | 기준 스키마 감사와 배포 전환을 함께 설계해야 함 |
| 중간 | 쿠폰 poison 명령의 메시지별 DLT 격리 | 실제 발생 가능성과 처리 계약을 먼저 정해야 함 |
| 배포 시 | Prometheus 접근 제한, K8s OAuth Secret 연결 | 현재 Oracle Compose 운영 경로에는 즉시 필요하지 않음 |

## 의도적으로 바꾸지 않은 것

- FCFS orchestrator·ledger·projection 분리는 원장 트랜잭션, 단건 복구, 후행 projection 책임이 있어 유지했다.
- `DashboardService`는 크지만 파일 길이만으로 나누지 않았다.
- 현재 실패 사례가 없는 Inbox/Outbox나 범용 repository·domain-event·strategy framework는 추가하지 않았다.
