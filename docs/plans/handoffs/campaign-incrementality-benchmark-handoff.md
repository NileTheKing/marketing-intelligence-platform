# Campaign Incrementality Benchmark Handoff

> Status: `active` (raw query and history-index A/B complete)
> Date: 2026-10-14

> 작업 인수인계 기록이다. 아래 branch·미커밋 상태는 2026-10-14 당시 정보이며,
> 현재 Git 상태를 뜻하지 않는다. 기능 설계는 [캠페인 효과 분석](../../architecture/campaign-incrementality-analysis-v1-plan.md)을 참고한다.

## Current goal

쿠폰 캠페인 성과 조회가 실행되는 동안 구매 기록 저장이 얼마나 영향을 받는지 측정한다.
단순 조회 시간 비교가 아니라 다음 세 조건의 20건 단위 Purchase 저장 트랜잭션 p95를 비교한다.

1. 성과 조회가 없는 기준선
2. 인덱스 없는 성과 조회와 동시 실행
3. 인덱스 적용 성과 조회와 동시 실행

InnoDB의 일반 일관 읽기는 Purchase 행 잠금을 잡지 않는다. 따라서 이 측정에서 쓰기 지연이 달라지면 행 잠금이 아니라 MySQL CPU, 버퍼 풀, 디스크 같은 공유 자원 경합으로만 해석한다. HTTP, Kafka, 애플리케이션 시작 시간은 범위 밖이다.

## Current implementation

- Benchmark: `core-service/src/test/java/com/axon/core_service/benchmark/CampaignRunIncrementalityIndexBenchmarkTest.java`
- Runner: `scripts/benchmark/run-campaign-incrementality-index-benchmark.sh`
- Gradle system property forwarding: `core-service/build.gradle`
- Data shape: Campaign Run target 100,000명, TREATMENT 90,000명, HOLDOUT 10,000명. Purchase profile은 100만, 500만 건을 측정했다.
- A/B index: `(user_id, purchase_at)` absent vs present
- Foreground writes are deliberately stored 30 days after the observed window. A/B read result가 동시 쓰기로 달라지지 않게 하기 위함이다.

## Findings so far

### Benchmark execution results (2026-10-14)

| Purchase rows | Read p50, no index | Read p50, index | Read p95, no index | Read p95, index | Foreground write p95 during overlap |
|---|---:|---:|---:|---:|---:|
| 1,000,000 | 706ms | 520ms | 757ms | 539ms | 8ms -> 6ms |
| 5,000,000 | 1,534ms | 524ms | 1,708ms | 605ms | 5ms -> 5ms |

Artifacts: `artifacts/benchmark/20261014-170325-campaign-incrementality-1m`, `artifacts/benchmark/20261014-170500-campaign-incrementality-5m`.

### Decisions

1. 성과 API는 target을 기준으로 `purchases`를 직접 left join하는 raw native SQL을 유지한다. Set-based 변형은 이 대상 규모에서 일관된 이점이 없었다.
2. `(user_id, purchase_at)` 복합 인덱스를 적용한다. 500만 건에서 인덱스 미적용은 `purchases` 500만 건 table scan 후 hash join을 수행했고, 적용 후에는 대상 10만 명의 구매 이력을 사용자별 lookup했다.
3. 커버링 인덱스는 적용하지 않는다. 읽기만 위한 넓은 인덱스가 구매 원장의 쓰기 비용과 저장 공간을 늘리는 트레이드오프에 비해 v1 요구사항에 과하다.
4. 현재 overlap은 한 개의 foreground writer만 측정했다. 저장 p95 악화는 관측되지 않았지만, 여러 구매 요청이 동시 실행될 때의 간섭은 아직 측정하지 않았다.

## Commands

```bash
scripts/benchmark/run-campaign-incrementality-index-benchmark.sh 1m
scripts/benchmark/run-campaign-incrementality-index-benchmark.sh 5m
```

현재 runner는 artifact 경로가 root `artifacts/benchmark/`에 남도록 구성되어 있다. 실행 전 `git status --short`로 기존 작업과 분리되지 않았음을 확인한다.

## Branch and workspace boundary

- Current branch: `main`
- No benchmark commit exists.
- The workspace contains unrelated, uncommitted campaign review, AI triage, observability, and load-test work. Do not reset, stash, or commit a broad diff.
- Benchmark-specific files are the three paths in **Current implementation** and this handoff document.
