# 과거 배포 자료

| 위치 | 내용 |
|---|---|
| `k8s/` | KT Cloud K2P의 Helm 값, Kubernetes 매니페스트와 이전 배포 워크플로 |
| `pinpoint/` | Pinpoint agent 설치와 수집기 연결 설정 |
| `compose/` | 이전 로컬 인프라·관측 스택과 Compose 백업 |

당시 구성의 참고 기록이며 현재 실행 안내는 루트 README와
[운영 문서](../docs/operations/oracle-compose-baseline-runbook.md)를 따른다.
이동된 파일의 상대경로는 현재 위치에서 실행할 수 있도록 재설계하지 않았다.
`k8s/deploy-workflow.yml`은 `.github/workflows/` 밖에 보관하므로 GitHub Actions에 등록되지 않는다.
