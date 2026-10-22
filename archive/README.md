# 과거 배포 자료

| 위치 | 내용 |
|---|---|
| `k8s/` | KT Cloud K2P의 Helm 값, Kubernetes 매니페스트와 이전 배포 워크플로 |
| `pinpoint/` | Pinpoint agent 설치와 수집기 연결 설정 |
| `compose/` | 이전 로컬 인프라·관측 스택과 Compose 백업 |
| `scripts/demo/` | 이전 시드·지연 비교 도구 |

당시 구성의 참고 기록이며 현재 실행 안내는 루트 README와
[운영 문서](../docs/operations/oracle-compose-baseline-runbook.md)를 따른다.
이동된 파일의 상대경로는 현재 위치에서 실행할 수 있도록 재설계하지 않았다.
`k8s/deploy-workflow.yml`은 `.github/workflows/` 밖에 보관하므로 GitHub Actions에 등록되지 않는다.

`scripts/demo/seed-power-demo.sh`는 정리 전에도 닫히지 않은 따옴표로
셸 구문 검사에 실패했다. `compare-latency.sh`는 이전 API 비교용이며 첫 줄의
shebang도 잘못되어 있다. 이번 작업은 이 도구들을 복구하거나 실행하지 않고 보존한다.

`compose/es-sink-connector.json`은 폐기된 토픽을 포함한 이전 커넥터 설정이다.
파일 이동은 실행 중인 Kafka Connect 커넥터 설정을 변경하지 않는다.
