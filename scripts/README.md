# 개발·운영 스크립트

저장소 루트에서 실행한다.

| 위치 | 용도 |
|---|---|
| `load-test/` | Oracle Compose 선착순 부하 실행, 준비와 결과 집계 |
| `benchmark/` | SQL·인덱스 비교와 별도 MySQL 실험 환경 |
| `demo/` | 시연 데이터 생성·정리와 요청 발생 |
| `demo/dashboard/` | 초기 대시보드용 데이터 조작·조회 도구 |
| `migrations/` | 날짜별 DB 스키마 변경 |
| `infrastructure/observability/` | OTel agent 설치와 관측 실험 |
| `test/`, `ci/` | 회귀 테스트 실행과 CI 결과 확인 |

Compose 부하 준비는 `load-test/prepare-load-test-compose.sh`에서 시작한다.
같은 폴더의 `prepare-load-test.sh`는 현재도 호출되는 공통 구현이다.

데모의 주 시드 실행 파일은 `demo/seed.py`다. 데이터를 초기화하므로
사용할 DB·Redis·Elasticsearch 설정을 확인한 뒤 실행한다.
`demo/dashboard/`는 이전 데모 도구를 모은 것으로, 현재 API·스키마와의
호환성을 이번 정리에서 재검증하지 않았다.

옛 K8s 도구와 사용하지 않는 비교 스크립트는
[과거 자료](../archive/README.md)에 보관한다.
