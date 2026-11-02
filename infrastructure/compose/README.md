# 추가 Compose 설정

저장소 루트에서 `compose.app.yml`을 첫 번째 파일로 지정해 실행한다.
빌드 경로와 마운트 경로는 첫 파일이 있는 저장소 루트를 기준으로 해석한다.

| 파일 | 용도 |
|---|---|
| `compose.analytics.yml` | Elasticsearch와 Kafka Connect 행동 분석 파이프라인 |
| `compose.metrics.yml` | Prometheus, Grafana, Alloy, Loki |
| `compose.otel.yml` | OTel Java agent와 Jaeger 트레이스 |
| `compose.resources.yml` | CPU·메모리 제한을 적용하는 실험 프로필 |
| `compose.diagnostic.yml` | Entry 진단 프로필 |
| `compose.loadtest-entry-observability-off.yml` | Entry 관측 비용을 제외하는 부하 실험 설정 |
| `compose.benchmark.yml` | 애플리케이션과 별도로 실행하는 SQL 실험용 MySQL |

```bash
docker compose -f compose.app.yml \
  -f infrastructure/compose/compose.analytics.yml \
  -f infrastructure/compose/compose.metrics.yml up -d
```

SQL 실험용 DB는 `scripts/benchmark/mysql-lab.py`가 별도 프로젝트
`axon-benchmark`로 관리한다. 다른 추가 설정과 달리 `compose.app.yml`과 합치지 않는다.
직접 실행할 때도 저장소 루트를 프로젝트 디렉터리로 지정한다.

```bash
docker compose --project-directory . -p axon-benchmark \
  -f infrastructure/compose/compose.benchmark.yml up -d
```

## 계획: Redis AOF 영속화 (2026-11-02)

Status: `local implementation` — Compose 설정에 적용했다. 기존 공유 환경이나
VM에는 적용하지 않았다. 2026-11-02 독립 임시 Redis와 별도 Docker 볼륨에서
정상 재시작 시 set·수량·TTL을, 쓰고 2초 후 강제 종료한 경우 수량 복구를
확인했다. 장애 직전 유실량을 측정한 실험은 아니다.

- 선착순 판정의 Redis 데이터를 재시작 후 복원하기 위해 `compose.app.yml`의
  Redis에 `appendonly yes`, `appendfsync everysec`을 설정했다. 현재
  명령은 `--save ""`이며 `/data`는 `redis_data` 볼륨이다. RDB 자동 저장은
  현행대로 끄는 방안을 기본으로 한다. 실제 런타임 설정과 이미지 버전은
  변경 전 확인한다.
- everysec은 성능과 내구성의 절충이며 장애 직전 약 1초분 유실 가능성을
  허용한다. 디스크 오류나 Redis 전체 유실까지 해결하지 않고 Redis와 DB/Kafka
  사이의 원자성도 보장하지 않는다. 기존 성능 실험은 AOF 적용 효과를 입증하지 않는다.
- 별도 Compose 프로젝트와 별도 볼륨으로 참여자 set·수량 key를 만든 뒤,
  AOF 활성화와 영속화 상태를 확인하고 Redis를 재시작해 데이터와 TTL을
  검증한다. 정상 재시작과 강제 종료를 구분하고, 유실 허용 범위를 주장할
  때는 실제 테스트 조건과 한계를 기록한다.
- 기존 데이터가 있는 환경에서는 설정 변경 후 곧바로 컨테이너를 재생성하면
  AOF가 없는 메모리 데이터를 잃을 수 있다. 런타임 설정과 데이터·볼륨을 확인하고,
  실행 중 AOF 활성화와 초기 생성 완료 확인, 백업/복원 절차를 별도 환경에서
  검증한 후 실제 환경의 적용 순서와 롤백을 기록한다.
  공유 Redis와 MySQL은 검증 목적으로 재생성하거나 초기화하지 않는다.
- 적용 이후의 성능 영향은 필요하면 같은 부하·환경에서 별도로 측정한다.
