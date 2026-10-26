# Axon Agent Guide

이 파일은 저장소 작업의 진입점이다. 상세 문서는 작업에 필요한 절만 읽는다.
`CLAUDE.md`는 다른 도구를 위한 동일 내용의 사본이며 CI에서 일치를 검사한다.

## 프로젝트와 경계

Axon은 선착순 트래픽을 수용하고, 행동 로그를 마케팅과 대시보드에 활용한다.
- `services/entry-service`: 요청 수신, Redis 선착순 판정, 결제 명령 발행 (`:8081`).
- `services/core-service`: 구매 후처리, 마케팅, 대시보드 (`:8080`).
- `services/ai-triage-service`: 실패 분석과 운영 승인 지원.
- `libs/common-messaging`: 메시지 계약. `infrastructure/`: 실행과 관측 설정.
- 결제 내부 로직은 다른 개발자 담당이다. 명시적인 요청 없이 변경하지 않는다.
- 이 저장소는 개발과 고도화용이다. 이력서, 포트폴리오 산출물은 별도 Obsidian vault에서 관리한다.

## 읽기 경로

먼저 [시스템 지도](docs/architecture-map.md)로 서비스 경계를 확인하고,
아래에서 해당 작업의 문서로 이동한다. 전체 문서를 순서대로 읽을 필요는 없다.

| 작업 | 먼저 확인할 문서 |
|---|---|
| 코드 위치와 변경 범위 | [코드 탐색](docs/architecture/domain-refactoring-map.md)의 컴포넌트 표 |
| 기능 설계와 결정 찾기 | [문서 목록](docs/catalog.md)에서 해당 주제의 정본 |
| 구매 후처리와 실패 처리 | [트랜잭션 경계](docs/architecture/fcfs-entry-purchase-transaction-boundary-handoff.md)의 현재 구조 |
| 관리자 화면 | [콘솔 디자인](docs/architecture/admin-console-design.md) |
| 실행과 배포 | [Compose 구성](infrastructure/compose/README.md), [운영 절차](docs/operations/oracle-compose-baseline-runbook.md)의 읽기 경로 |
| 실험과 성능 수치 확인 | [증거 확인 절차](docs/experiment-evidence-workflow.md) |
| T 파일 작성과 수정 | 별도 vault의 기존 T 파일과 비공개 작성 지침 |

메모리는 도구와 세션에 따라 제공 여부가 다르며 보조 자료다.
개발에 필요한 지속적인 결정은 해당 주제의 저장소 문서에 남긴다.
비공개 vault 경로와 비밀값은 공개 문서에 넣지 않는다.

## 작업과 확인

- 변경 전 가정과 성공 기준을 짧게 정한다. 범위나 데이터 안전성에 영향을 주는 불확실성은 확인하고, 일상적인 구현 선택은 기존 관례를 따른다.
- 요청을 해결하는 최소 범위만 바꾼다. 관련 없는 정리, 추상화, 기능 추가를 섞지 않는다.
- 버그 수정은 재현 가능한 확인을 남기고, 리팩토링은 관련 회귀 테스트로 동작 유지를 확인한다.
- Java 테스트는 저장소 루트에서 `services/core-service/gradlew -p services/core-service test --no-daemon`, Entry는 경로를 `entry-service`로 바꿔 실행한다. 변경 범위에 따라 `--tests`로 집중 실행하고 필요한 통합 테스트가 실제 실행됐는지 확인한다. 기준은 `.github/workflows/ci.yml`이다.
- 로컬 Compose 프로젝트는 `docker`다. 공유 MySQL과 Redis는 재생성하지 않는다. 서비스만 바꿀 때는 기존 Compose 구성에 `--no-deps`를 사용한다.
- `scripts/demo/seed.py`는 데이터를 초기화한다. 단순 화면 확인을 위해 기존 데이터를 덮어쓰지 않는다.
- 실험은 기존 증거로 답할 수 있는지 먼저 확인한다. 로그 전체를 출력하거나 이유 없이 다시 실행하지 않는다.

## 근거와 문서 관리

- 현재 구현은 코드와 테스트, 배포 상태는 날짜가 있는 배포 기록과 실제 환경, 과거 성능은 당시 커밋과 실험 증거로 확인한다. 서로 다른 시점의 근거를 섞지 않는다.
- `active`는 현재 참조 문서이지 구현 완료 표시가 아니다. 절별 구현, 배포, 검증 날짜를 확인한다.
- `reference`는 대조할 참고 자료, `legacy`는 과거 구조, `draft`는 미확정 제안, `superseded`는 대체된 기록이다. 코드와 충돌하면 충돌을 밝히고 문서를 정정한다.
- 일반 문서 안의 과거 작업 지시와 완료 조건은 기록이다. 현재 작업에 자동으로 적용하지 않는다.
- 같은 주제의 기존 정본을 먼저 갱신한다. 공개할 설계·실험·운영 문서만 `docs/catalog.md`에 등록한다. 내부 감사, 작업 계획, 세션 인수인계와 채용 준비 메모는 Git에서 제외된 로컬 보관 영역에 두며 공개 목록에 연결하지 않는다.
- 문서를 대체하면 기존 문서에 `superseded`와 후속 경로를 남긴다. 유효한 설계와 과거 실험 증거는 완료됐다는 이유로 보관함에 옮기지 않는다.
- 문서 작업 완료 시 신규, 수정, 대체 문서와 해결되지 않은 사실 충돌을 보고한다.
- 진입 문서의 상대 Markdown 링크와 지침 사본은 `python3 scripts/ci/check-doc-navigation.py`로 확인한다. 외부 URL, 절 앵커, 본문에 적힌 코드 경로와 문서의 사실성은 이 검사의 범위가 아니다.
