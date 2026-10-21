# 선착순 Redis 처리 비교: 개별 호출, WATCH, Lua

> 상태: active evidence
> 정리: 2026-10-21. 로컬 후속 실험이며 운영 VM이나 2025년 개발 당시의 API 성능은 아니다.

## 핵심 결과

각 행은 해당 실험 안에서 비교한 결과다. 날짜, Redis 버전, 워밍업과 집계 방식이 달라 행 사이의 절대 시간을 직접 비교하지 않는다.

| 비교 | 측정 범위 | 비교 방식 p95 | Lua p95 | 집계 |
|---|---|---:|---:|---|
| WATCH + MULTI/EXEC vs Lua | Redis 판정 구간 | 141.929ms | 1.335ms | 10/13, 64 workers, 실행별 p95의 3회 평균 |
| 개별 Redis 호출 vs Lua | Redis 판정 구간 | 9.445ms | 3.273ms | 10/21, 실행별 p95의 3회 중앙값 |
| 개별 Redis 호출 vs Lua | 선착순 API 서버 내부 | 34.890ms | 22.596ms | 10/21, 실행별 p95의 3회 중앙값, 35.2% 단축 |
| 개별 Redis 호출 vs Lua | HTTP 클라이언트 응답 | 44.907ms | 33.903ms | 10/21, 실행별 p95의 3회 중앙값, 24.5% 단축 |

WATCH와 Lua의 기존 비교는 HTTP API 실험이 아니다. 이 자료에서 WATCH 대비 API 응답시간 63% 단축이라는 주장을 뒷받침하는 원본은 확인하지 못했다.

## 무엇을 비교했나

- 개별 호출: `SADD`로 중복 확인과 참여자 등록, `INCR`로 정원 판정, 초과하면 `SREM`과 `DECR`로 복구한다.
- WATCH: 참여자 집합과 카운터를 감시하고 중복과 정원을 조회한 뒤 `MULTI/EXEC`로 변경한다. 충돌하면 1~3ms 무작위 대기 후 재시도한다.
- Lua: 현재 `EntryReservationService`의 스크립트로 중복 확인, 카운터 증가, 초과 시 복구를 한 번에 실행한다.

`INCR` 반환값으로 정원을 판정하는 것과, 참여자 집합 및 카운터 변경을 함께 끝내는 것은 구분한다. Lua는 후자의 상태 변경을 묶으며, 개별 호출 대비 네트워크 왕복도 줄인다. WATCH는 같은 상태를 보호하지만 공유 키 경합 시 재시도 비용이 생긴다.

이번 개별 호출 기준은 현재 Lua와 최종 동작을 맞춘 비교용 구현이다. 과거 커밋 `8a100d7`에는 초과 시 `SREM`만 있고 `DECR`가 없으므로, 당시 소스를 그대로 재실행한 결과라고 부르지 않는다.

## 1. WATCH + MULTI/EXEC vs Lua: 기존 Redis 실험

- 날짜: 2026-10-13, 코드 기준 `c8634b3`와 당시 실험용 테스트.
- 환경: 로컬 macOS, Java 21.0.8, Redis 8.2.1 임시 서버.
- 부하: 서로 다른 사용자 300명, 정원 100명, workers 1/16/64.
- 클라이언트: Spring Data Redis와 Lettuce, 풀 설정 최대 8개.
- 전략별 워밍업 1회, 순서를 교차한 측정 3회. 표는 실행별 지표의 평균.
- HTTP, JWT, 토큰 발급, Kafka는 포함하지 않는다.

| Workers | 방식 | 요청/초 | 전체 요청 p95 | 승인 요청 p95 | WATCH 충돌 재시도/실행 |
|---:|---|---:|---:|---:|---:|
| 1 | Lua | 5,892.6 | 0.220ms | 0.236ms | 0 |
| 1 | WATCH | 1,545.9 | 0.873ms | 1.186ms | 0 |
| 16 | Lua | 33,060.3 | 0.889ms | 0.889ms | 0 |
| 16 | WATCH | 2,035.5 | 38.916ms | 53.966ms | 433.3 |
| 64 | Lua | 51,193.1 | 1.335ms | 1.181ms | 0 |
| 64 | WATCH | 1,912.6 | 141.929ms | 109.517ms | 537.7 |

모든 실행에서 승인 100명, 매진 200명, 카운터와 참여자 수 100을 확인했다. 원본 JUnit은 테스트 3개 통과, skip 0이다. WATCH가 항상 이 비율로 느리다는 뜻이 아니라, 한 집합과 카운터를 동시에 변경하는 이 실험에서 충돌과 재시도가 증가한 결과다.

당시 자료는 다른 워크트리 `609e`에서 찾아 아래 Git 추적 경로로 보관했다. WATCH 코드는 참고용 원본이며 현재 테스트 소스에 편입하거나 재실행한 것은 아니다.

- 원본 결과: `docs/devlog/evidence/redis-reservation-comparison/watch-20261013/result.xml`
- 당시 코드: `docs/devlog/evidence/redis-reservation-comparison/watch-20261013/RedisWatchMultiComparisonIT.java.reference`

## 2. 개별 호출 vs Lua: Redis 구간

- 날짜: 2026-10-21, 기준 HEAD `388317b`.
- 환경: 로컬 macOS ARM64, Java 21, Lettuce 6.6, 독립된 Redis `7-alpine` Testcontainers.
- 요청 300건, 정원 100명, workers 64. 전략별 워밍업 3회, AB/BA 순서로 측정 3회.
- 동일한 `StringRedisTemplate`과 연결 설정을 사용하며 실행 사이에 테스트 Redis 상태를 초기화한다.
- 요청별 Redis 처리 시간과 전체 완료 시간을 측정한다. 작업 제출 대기, 초기화, 결과 확인은 제외한다.

| 방식 | 실행별 p95 중앙값 | p95 범위 | 처리량 중앙값 |
|---|---:|---:|---:|
| 개별 호출 | 9.445ms | 9.058~9.722ms | 9,187건/초 |
| Lua | 3.273ms | 2.881~3.439ms | 23,942건/초 |

p95 65.4% 단축, 처리량 약 2.6배. 워밍업과 측정 모두 승인 100명, 매진 200명, 카운터와 참여자 수 100, 오류 0이다. 테스트 1개 통과, skip 0.

이 비교는 정상 요청의 성능 측정이다. 프로세스 강제 종료나 네트워크 단절을 주입하지 않았으므로, 장애 시 복구까지 실증한 결과는 아니다.

- 코드: `entry-service/src/test/java/com/axon/entry_service/service/RedisLegacyLuaComparisonIT.java`
- Git 보관 원본 CSV: `docs/devlog/evidence/redis-reservation-comparison/redis-20261021/per-run.csv`
- 결과, 실행 명령, 원본 CSV: `artifacts/benchmark/redis-legacy-lua-20261021-115257/`

## 3. 개별 호출 vs Lua: 실제 선착순 HTTP API

`POST /api/v1/entries`를 실제 Spring Boot 서버에 호출한다. 예약 Redis 연산만 테스트용 어댑터로 교체하고 나머지는 운영 코드를 사용한다.

```text
HTTP 요청 -> JWT 인증 -> 메타 캐시 조회 -> 기존 토큰 조회
          -> 개별 Redis 호출 또는 Lua
          -> 예약 토큰 저장 -> JSON 응답
          -> 승인 이벤트의 비동기 Kafka 발행
```

- 같은 서버 프로세스와 독립된 Redis/Kafka 컨테이너에서 비교한다. Kafka는 Entry 테스트 의존성에 전용 컨테이너 모듈이 없어 단일 KRaft 컨테이너로 구성했다.
- 요청 300건, 정원 100명, HTTP workers 64. 전략별 워밍업 3회, 순서를 교차한 측정 3회.
- 메타 데이터는 양쪽 모두 Redis 캐시 적중, 추가 자격 필터 없음, JWT와 요청 본문은 측정 전에 준비한다.
- 실제 JWT, 토큰 저장, 비동기 이벤트 발행을 사용한다. 서버 요청 처리 스레드 300개 표본 모두 가상 스레드임을 확인했다.
- 서버 지표는 보안 필터부터 컨트롤러와 응답 직렬화까지의 필터 체인 처리 시간이다. 소켓, 네트워크 및 필터 진입 전 대기는 제외한다.
- HTTP 클라이언트 지표는 로컬 루프백 통신을 포함한다. 토큰 검증용 추가 Redis 조회와 Kafka 결과 확인은 부하 구간이 끝난 뒤 수행한다.
- 로컬 CPU는 제한하지 않았다. VM의 1.5 vCPU, 3,000명 이벤트, 고정 RPS 성능과는 별개의 실험이다.

| 실행별 p95의 중앙값 | 개별 호출 | Lua |
|---|---:|---:|
| 서버 내부, 전체 요청 | 34.890ms | 22.596ms |
| 서버 내부, 승인 요청 | 35.006ms | 24.184ms |
| 서버 내부, 매진 요청 | 32.728ms | 18.158ms |
| HTTP 클라이언트, 전체 요청 | 44.907ms | 33.903ms |

전체 요청의 서버 내부 p95는 35.2%, HTTP 응답 p95는 24.5% 단축됐다. 서버 p95의 실행 간 범위는 개별 호출 27.074~49.097ms, Lua 18.419~24.553ms다. 처리량 중앙값은 2,180건/초에서 2,918건/초로 변했다.

매 실행마다 서버와 클라이언트가 승인 100명, 매진 200명으로 일치하고, 실제 예약 토큰 100건과 승인 행동 이벤트 100건, Redis 카운터와 참여자 수 100을 확인했다. 테스트 1개 통과, skip 0.

API 완료는 Kafka 소비나 구매 DB 저장 완료를 뜻하지 않는다. 별도 API인 결제 prepare/confirm, Core/MySQL/Elasticsearch, nginx와 외부망은 이번 범위에 없다.

- 코드: `entry-service/src/test/java/com/axon/entry_service/controller/EntryReservationHttpComparisonIT.java`
- Git 보관 원본 CSV: `docs/devlog/evidence/redis-reservation-comparison/http-20261021/per-run.csv`
- 결과와 원본 CSV, 로그, JUnit: `artifacts/benchmark/entry-http-legacy-lua-20261021-122157/`
- 실행: `HTTP_BENCHMARK_DIR=<절대 결과 경로> ./gradlew test -PincludeRedisExperiments=true --tests '*EntryReservationHttpComparisonIT' --rerun-tasks` (`entry-service`에서 실행, Docker 필요)

## 연결 풀과 결과 사용 범위

`docs/devlog/evidence/`의 원본은 Git으로 보관한다. `artifacts/benchmark/`는 로컬 실행 상세 자료이며 Git에 포함되지 않는다. 이 통합 문서가 비교 설명의 정본이다.

풀 설정 8개와 workers 64개는 서로 다른 값이다. Spring Data Redis의 Lettuce 기본값은 일반 명령에 공유 native 연결을 사용한다. `SADD`, `INCR`, Lua가 각각 풀 연결을 독점하는 구조가 아니므로, 풀 8개를 모든 요청의 동시성 제한으로 설명하지 않는다. WATCH 트랜잭션은 전용 연결 경로를 사용하므로, 두 방식의 실질적인 연결 사용까지 같다고 주장하지 않는다.

- WATCH 결과는 충돌 재시도 비용을 설명하는 Redis 구간 근거로 사용한다.
- 개별 호출과 Lua의 Redis 결과는 왕복 감소 효과를 설명하는 근거로 사용한다.
- API 성능을 설명할 때는 3번의 서버/HTTP 구분과 로컬 조건을 함께 사용한다.
- Lua는 Redis 내부 명령 실행 오류를 자동 롤백하지 않으며, 응답 유실 시 성공 여부의 불확실성까지 해결하지 않는다.
- 비교 실험은 자동 CI 대상이 아니다. 두 신규 비교 테스트는 `redis-experiment` 태그로 기본 테스트에서 제외된다.
