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
