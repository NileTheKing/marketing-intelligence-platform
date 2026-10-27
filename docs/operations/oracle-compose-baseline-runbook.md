# Oracle Compose Baseline Runbook

> 읽기 안내: 이 문서는 실행 절차와 날짜별 실험 기록을 함께 보존한다. 리소스·인스턴스 수·consumer 동시성은 각 실험의 통제 조건이며 구조적 상한이 아니다. 오래된 실행 결과는 해당 커밋과 환경에 한정한다.

Status: active

## 읽기 경로

이 문서에는 운영 규칙과 날짜별 실행 증거가 함께 있다. 아래에서 필요한 절로 이동한다.
문서의 최신 수정일은 VM 상태 확인일이 아니며, 배포 기록은 해당 날짜의 관측이다.

| 목적 | 읽을 절 |
|---|---|
| 배포 방식과 실행 정책 | [CI/CD Execution Policy](#cicd-execution-policy) |
| 앱 재생성 후 nginx 연결 확인 | [Mandatory Nginx Restart After App Recreate](#mandatory-nginx-restart-after-app-recreate) |
| 로그인 설정 확인 | [네이버 로그인 환경변수](#네이버-로그인-환경변수) |
| 구매·쿠폰 최신 배포 | [10월 27일 구매 확정과 쿠폰 처리](#2026-10-27-구매-확정과-쿠폰-처리-배포) |
| 데모 데이터와 마지막 오류 수정 기록 | [10월 24일 데이터 전환](#2026-10-24-관리자-시연-데이터-전환), [증분 분석과 편집 수정](#2026-10-24-증분-분석-진입과-쿠폰-활동-편집-오류-수정) |
| 부하 실험 준비 | [Source Boundary](#source-boundary), [Baseline Assumptions](#baseline-assumptions), [Resource Profile](#resource-profile) |
| 과거 실행 재현 | 해당 날짜와 커밋의 기록. `Pending`도 당시 상태이며 현재 할 일로 자동 적용하지 않는다. |

부하 실험 준비 명령은 데이터와 실행 환경을 바꿀 수 있다. 일반 배포나 화면 확인의
필수 절차로 실행하지 않는다. 현재 리소스는 적용한 Compose 파일과 override를 확인한다.

## Purpose

Run a reproducible FCFS purchase-path baseline on the Oracle VM Docker Compose environment.

This runbook is separate from the older k8s-era load-test scripts and previous KT Cloud K2P results. Do not compare Oracle VM numbers directly with K2P numbers.

## Source Boundary

Use these scripts for the current Oracle VM Compose baseline:

- `scripts/load-test/prepare-load-test-compose.sh`
- `scripts/load-test/run-external-compose-baseline.sh`
- `scripts/load-test/run-baseline-compose.sh`
- `scripts/load-test/check-results-compose.sh`

The following scripts are historical K8s references:

- `archive/k8s/scripts/check-results.sh`
- `archive/k8s/scripts/monitor-load-test.sh`

`scripts/load-test/prepare-load-test.sh` remains active: the Compose preparation
wrapper calls it with `REDIS_MODE=docker`. Do not archive it with the K8s-only tools.

## 2026-10-23 관리자 데모 배포

- 배포 코드: `3db045b` (`main`). VM의 기존 이미지·Compose·환경 파일과 DB를 백업한 뒤
  Core·Entry 이미지를 다시 빌드했다. DB·Redis·Kafka·관측 컨테이너는 재생성하지 않았다.
- 실행 구성: `compose.app.yml`, `infrastructure/compose/compose.resources.yml` 및 기존 VM의
  Entry 진단 override. Core 1.2 CPU, Entry 1.5 CPU 설정을 유지했다.
- 기존 DB와 대조한 뒤 미적용 migration 6개만 실행했다: `2026-09-27` 휴면 캠페인,
  `2026-10-14` 장바구니 후보, `2026-10-16` 상품 후기, `2026-10-14` 쿠폰 증분 분석,
  `2026-10-19` 자동 쿠폰과 활동 연결, `2026-10-19` 참여자 조회 인덱스.
  구형 후보 인덱스 교체 스크립트는 최신 생성 스크립트에 이미 반영돼 있어 실행하지 않았다.
- Core·Entry health `UP`, nginx 설정 검사 통과. 공개 브라우저에서 `/shop`의 관리자 데모
  링크와 `/demo/admin` 진입, 캠페인 목록 데이터 표시를 확인했다.
- 데모 권한으로 주요 관리 화면 및 조회 API는 200, Core 변경 요청과 Entry 결제 요청은
  403으로 거부됐다. 네이버 실제 Client ID와 등록된 HTTPS 콜백 주소도 유지됐다.
- 남은 운영 제한: 현재 VM에 Elasticsearch 컨테이너가 없어 `DashboardService`의
  ES 조회에서 연결 오류가 발생한다. 조회 API의 200 응답은 ES 기반 지표까지 정상이라는
  뜻이 아니며 일부 행동 분석 값은 기본값으로 표시된다. 이번 배포에서 ES·Connect는
  추가 기동하지 않았다.
- 배포 전 백업 위치는 VM 저장소의 `.git/axon-last-deploy-backup`에 기록했다.
  비밀값과 발급된 데모 토큰은 실행 출력이나 이 문서에 기록하지 않는다.

## 2026-10-23 관리자 대시보드 오류 수정

- 최초 수정 배포: `b1b0c2a`. 이벤트 목록 JavaScript의 동적 빈 상태 문구까지 반영한
  최종 배포 코드는 `3267ceb`다. Core 이미지만 재빌드·재생성하고 nginx를 재시작했다.
  DB migration과 시연 데이터 생성은 실행하지 않았으며 기존 실험 기록을 보존했다.
- 초기 데모 배포의 행동 통계 0 기본값을 수정했다. ES 조회 실패 시 관련 수치와 그래프는
  `null`을 반환하고 화면에 조회 불가 안내와 `-`를 표시한다. MySQL 구매·매출 조회는 유지한다.
- 캠페인 기간 선택과 SSE의 기간 적용을 수정했다. VM API에서 1일 구매 0건,
  30일 구매 557,275건을 확인했고 공개 브라우저의 30일 표시와 일치했다.
  이 숫자는 기존 합성 부하 데이터이며 사업 성과로 해석하지 않는다.
- 발송 이력 없는 증분 효과 화면의 무설명 리다이렉트를 없애고 빈 상태를 표시한다.
  이벤트 빈 상태에서도 읽기 전용 데모에 없는 생성 버튼을 안내하지 않는다.
- 로컬 서비스 테스트 6개, 페이지 서비스 테스트 3개, 실제 MySQL/Redis 기반 데모 통합
  테스트 4개 통과. JavaScript 기간 변경 시 조회/SSE 파라미터와 이전 스트림 종료도 확인했다.
- VM Core health `UP`, 행동 통계 `null`, 구매 집계 유지, 데모 변경 요청 403을 확인했다.
  공개 브라우저에서 기간 변경과 오류 안내, 증분 효과 빈 상태를 확인했다.
- ES·Connect 미가동과 시연 데이터 부재는 여전히 남아 있다. 기존 `scripts/demo/seed.py`는
  테이블 초기화가 포함되므로 현재 VM에 그대로 실행하지 않는다.
- 이전 Core 이미지는 VM의 `axon-core-dashboard-rollback:20261023` 태그로 보관했다.

## 2026-10-24 관리자 콘솔 디자인 배포

- 배포 코드: `56931f8` (`main`). 화면별 로컬 확인과 관리자 데모 통합 테스트 4개 통과 후
  push하고 VM에서 Core만 재빌드·재생성했다. nginx는 새 Core 연결을 위해 재시작했다.
- 기존 Core 이미지는 `axon-core-console-rollback:20261024`로 보관했다.
  환경 파일과 교체 전 Core 설정은 VM의 보호된 배포 백업 디렉터리에 보관하며 공개하지 않는다.
- DB migration과 데이터 생성은 실행하지 않았다. Entry, MySQL, Redis, Kafka는 재생성하지 않았다.
- 교체 전후 Core 환경변수와 CPU 제한이 동일함을 확인했다. Core와 Entry health는 `UP`이다.
- 데모 권한으로 전체 개요, 캠페인 관리, 쿠폰, 행동 이벤트, 캠페인 대시보드와 증분 효과
  6개 화면의 200 응답, 공통 콘솔 레이아웃과 읽기 전용 배너를 확인했다.
  `/admin`은 전체 개요로 연결되고, 활동 생성·모니터링 접근과 변경 요청은 403으로 차단됐다.
- 캠페인 조회 API의 선택 기간과 조회 불가 행동 통계의 `null` 처리를 확인했다.
  공개 HTTPS 주소에서도 새 캠페인 대시보드와 공통 사이드바가 표시됐다.
- 데이터는 기존 VM 실험 데이터다. ES 미가동과 시연 데이터 전환은 이번 배포 범위 밖이다.
  로컬 확인 중 발견한 기존 기능 이슈는 [콘솔 디자인 기록](../architecture/admin-console-design.md)에 남겼다.

## 2026-10-24 관리자 시연 데이터 전환

- VM `axon_db`에 누적된 TPS 실험 데이터를 로컬의 기존 업무 시연 데이터로 교체했다.
  DB 연결 대상과 VM 테이블 구조는 유지했다. README와 캠페인 이름이 같은 데이터이며,
  README 촬영 당시의 동일한 스냅샷이라고 확인된 것은 아니다.
- 전환 전 전체 DB를 백업하고 격리된 MySQL에 실제 복원했다. 기존 구매와 참여 기록
  각각 557,275건을 포함한 35개 테이블의 건수를 기록했다. 전환 직전 백업도 별도로 보관했다.
- VM 스키마에 시연 데이터를 사전 적재해 25개 외래키 관계를 확인했다. 기존 스키마에만
  남은 `marketing_rules`의 필수 보상 컬럼 2개는 연결된 액션 값으로 채웠다.
  실제 네이버 로그인 계정 1개는 ID를 유지해 보존했고, 로컬의 Redis 대사 이슈는 옮기지 않았다.
- Entry 수신을 중지하고 Kafka lag 0을 확인한 뒤 Core를 중지했다. 데이터 교체 후
  35개 테이블의 건수가 사전 적재 결과와 일치했다. `campaign:*` Redis 키는 기존 값을
  백업한 뒤 무효화하고, 시연 활동의 선착순 카운터와 참여자 집합을 재구성했다.
- 첫 전환은 건수 확인 스크립트의 표준입력 연결 누락으로 확인이 실패해 이전 DB를
  자동 복원했다. 구매 557,275건과 회원 559,521건의 복구를 확인한 뒤 스크립트를
  수정해 재전환했다. 이전 데이터 백업과 전환 스크립트는 VM의 보호된 배포 백업 경로에 보관한다.
- 최종 데이터: 캠페인 5개, 활동 10개, 쿠폰 7개, 구매 65,145건,
  시연 회원 100,000명과 보존한 로그인 계정 1개. 실험 캠페인은 목록에서 제거됐다.
  코드와 Git의 실험 근거, Kafka 토픽과 소비 offset은 변경하지 않았다.
- Core와 Entry health `UP`, Kafka 명령 토픽 3개 파티션의 lag 0을 확인했다.
  관리자 캠페인·쿠폰·대시보드 조회와 데모 변경 요청 403을 확인했다.
  확인 당시 최근 30일 개요는 구매 2,807건, 거래액 324,839,700원, ROAS 117%였다.
  이 수치는 합성 시연 데이터이며 실제 사업 성과나 성능 측정 수치가 아니다.
- 발송 완료된 증분 분석 실행 1~4는 200이다. 가장 최신인 실행 7은 승인 대기 상태라
  API가 `Campaign performance is available after dispatch`와 409를 반환한다.
  기본 화면이 분석 불가능한 최신 실행을 선택하는 문제는 별도 후속 수정이다.
- Elasticsearch와 Connect는 기동하지 않았다. 방문·행동 지표는 계속 조회 불가 상태이며,
  MySQL 데이터 전환만으로 행동 분석까지 복구된 것은 아니다. 시연 데이터의 날짜도
  재생성하거나 현재 시점으로 이동하지 않았다.

## 2026-10-24 증분 분석 진입과 쿠폰 활동 편집 오류 수정

- 배포 코드: `1019097` (`main`). Core만 재빌드·재생성하고 nginx를 재시작했다.
  이전 Core 이미지는 `axon-core-admin-fixes-rollback:20261024`로 보관했다.
- 기본 증분 분석 진입은 분석 가능한 최신 실행 4를 선택한다. 승인 대기 실행 7은
  선택 목록에서 제외되며, 직접 링크로 열면 조건 안내를 표시하고 분석 요청을 보내지 않는다.
  쿠폰 활동 편집은 기존 값 로딩과 저장 데이터 구성에서 공유 모듈 함수를 올바르게 참조한다.
- Java 테스트 6개, JS 테스트 3개, 구문 검사와 JAR 빌드가 통과했다.
  VM에서 실제 SSR 응답과 배포 JS, 정상 실행 분석 API 200, 데모 변경 요청 403을 확인했다.
- Core health는 `UP`이며 환경변수와 CPU 제한은 교체 전후 동일하다.
  시연 DB, Entry, Redis, Kafka 및 ES 미기동 상태는 변경하지 않았다.

## 2026-10-27 구매 확정과 쿠폰 처리 배포

- 배포 기준 커밋: `da578e7` (`main`). Core는 `20a9186`에서 빌드한 이미지,
  Entry는 `da578e7`에서 빌드한 이미지다. 두 커밋 사이 Core 코드는 동일하다.
- 구매 후처리의 원본 순서 유지, 쿠폰 직접 발급 제한, 장바구니 발행 실패의 즉시 반복 방지,
  쿠폰 적용 조건 재확인, 일반 구매 POST 확정/GET 결과 조회를 반영했다.
- 배포 전 Core 전체 테스트는 325개 통과했고 opt-in 실험·외부 연동 9개는 제외됐다.
  Entry 전체 68개와 필수 Redis 통합 테스트, JS 8개도 통과했다.
  최종 [GitHub CI](https://github.com/NileTheKing/marketing-intelligence-platform/actions/runs/40442378518)는 성공했다.
- 최초 CI에서 발견한 비쿠폰 캐시 회귀는 `da578e7`에서 수정했다. 발급 방식이 없는
  쿠폰 활동만 Core에서 다시 조회하며 선착순 활동의 캐시는 그대로 사용한다.
- 교체 전 앱 이미지·환경과 DB 스키마를 보호된 VM 백업 경로에 보존했다.
  롤백 이미지 태그는 `axon-core-rollback:20261027-purchase`, `axon-entry-rollback:20261027-purchase`다.
- `scripts/migrations/2026-10-27-shop-purchase-requests.sql`을 실제 DB에 적용해
  InnoDB 요청 기록 테이블을 추가했다. 기존 테이블 데이터 초기화나 데모 seed는 실행하지 않았다.
- 기존 Compose 파일·override로 Core와 Entry만 `--no-deps --no-build` 재생성하고 nginx를
  재시작했다. CPU 제한 Core 1.2, Entry 1.5와 기존 메모리·환경변수가 동일함을 확인했다.
  DB·Redis·Kafka·분석 저장소 및 같은 VM의 다른 서비스는 재생성하지 않았다.
- 실제 VM 확인: Core/Entry health 200, 인증 checkout 200과 CSRF 토큰 표시,
  CSRF 없는 POST 403, 요청 가격 입력 400, 없는 상품 확정 404와 요청 기록 롤백,
  과거 성공 URL 400, 없는 구매 결과 404, 직접 쿠폰 대상이 아닌 활동 요청 400.
- 확인 전후 구매는 `65,145`건, 새 요청 기록은 `0`건이었다. 정상 구매를 새로 생성하거나
  쿠폰을 실제 발급하는 운영 smoke는 실행하지 않았다. 동시 구매·멱등성은 로컬 격리 DB 테스트 근거다.
- 공개 HTTPS `/shop`, `/js/shop-checkout.js`의 200 응답을 확인했다. 실제 PG 연동은 여전히 없다.

## 네이버 로그인 환경변수

운영 VM의 `/home/ubuntu/apps/axon/.env`에는 실제 발급받은 `NAVER_CLIENT_ID`와
`NAVER_CLIENT_SECRET`가 필요하다. `compose.app.yml`의 `local-client-id`,
`local-client-secret`는 로컬 기동용 기본값이며 네이버 로그인에는 사용할 수 없다.
`.env`를 수정한 뒤에는 기존 컨테이너의 단순 restart가 아니라, 기존 배포와 같은
Compose 파일·환경을 사용한 Core 컨테이너 재생성이 필요하다.

인증 진입점 `/oauth2/authorization/naver`가 보내는 `redirect_uri`와 네이버 개발자센터
Callback URL이 일치하는지도 확인한다. 현재 공개 도메인의 예상값은
`https://axon.opicnic.xyz/login/oauth2/code/naver`다. Secret이나 인증 토큰은 로그·문서에
기록하지 않는다. `client info invalid`라면 콜백 실패로 단정하지 말고 먼저 실제
인증 요청이 개발용 client ID를 쓰는지 확인한다.

## CI/CD Execution Policy

Status: active

- `.github/workflows/ci.yml` runs automatically on pushes to `main` and pull requests. It verifies tests, required Testcontainers suites, Docker Compose configuration, and image builds; it does not change the VM.
- `.github/workflows/deploy-compose.yml` is the current Oracle VM deployment workflow. It originally ran automatically after a successful CI workflow, but that `workflow_run` trigger was disabled on 2026-07-01 to avoid unintended rebuilds during repeated load tests and frequent code changes.
- Current Oracle deployment is manual. Run `Deploy to Oracle VM Compose` through GitHub Actions (`workflow_dispatch`) or deploy directly on the VM when an explicit deployment is intended.
- The Actions deployment connects to the VM over SSH, pulls `origin/main`, runs `docker compose up -d --build`, restarts `axon-nginx`, and checks Core, Entry, and nginx health.
- CI failure is a visible verification failure, but it does not technically prevent the manual deployment workflow or a direct VM deployment. Check CI before triggering either manual path.
- `archive/k8s/deploy-workflow.yml` preserves the legacy KT Cloud K2P workflow. It was manual-only before archival and is no longer registered in GitHub Actions. It is not the current Oracle deployment path.

Do not re-enable automatic CI-success deployment while load-test artifacts or the running VM state must be preserved across frequent commits. Re-enable it only after automatic deployment is again the desired release policy.

## Baseline Assumptions

- Axon services run on the Oracle VM Docker Compose environment.
- Official baseline/final load is generated from an external client machine, not from the same VM.
- Core health is `UP`: `http://127.0.0.1:8080/actuator/health`
- Entry health is `UP`: `http://127.0.0.1:8081/actuator/health`
- MySQL is exposed on `127.0.0.1:3306`.
- Redis container name is `axon-redis`.
- k6 is installed on the external client machine for official measurement.
- Official before/after baseline runs use `compose.app.yml` plus `infrastructure/compose/compose.resources.yml`.

If k6 runs on the same VM, its CPU, network, and Docker overhead become part of the measured environment. Use same-VM k6 only for fast isolation/debugging. Do not use same-VM k6 numbers as the headline baseline.

## Resource Profile

Use the same resource profile for baseline and final measurement.

Default `infrastructure/compose/compose.resources.yml` limits:

| Service | CPU | Memory | JVM heap |
|---|---:|---:|---|
| core-service | 1.2 | 3GiB | `-Xms512m -Xmx2g` |
| entry-service | 0.6 | 2GiB | `-Xms256m -Xmx1536m` |
| mysql | 0.75 | 4GiB | n/a |
| kafka broker | 0.6 | 4GiB | image default |
| kafka controller | 0.25 | 1GiB | image default |
| redis | 0.25 | 1GiB | n/a |
| axon-nginx | 0.1 | 256MiB | n/a |

This 3.75 vCPU profile is intended for a 4 vCPU Oracle VM. It leaves CPU headroom for the host OS, Docker, host nginx, SSH, and short debug commands. Do not compare runs that use different resource profiles as a single before/after result.

## First Baseline Command

Preferred official path: run k6 from the Mac against the public route through the external baseline wrapper.

Default end-to-end purchase path:

```bash
cd "$AXON_REPO_ROOT"
FLOW=payment MAX_VUS=100 ./scripts/load-test/run-external-compose-baseline.sh 1000 1
```

Reservation-only hot path:

```bash
cd "$AXON_REPO_ROOT"
FLOW=reservation MAX_VUS=100 ./scripts/load-test/run-external-compose-baseline.sh 1000 1
```

The wrapper performs the following sequence:

1. Wait until Core's Kafka consumer lag and pending database connections remain at zero across consecutive samples. This prevents a prior run's asynchronous messages from being mixed into the next run.
2. Reset seed data and regenerate JWT tokens on the VM.
3. Verify token count on the VM.
4. Copy the fresh token file to the Mac.
5. Verify token count locally.
6. Run external k6 against `https://axon.opicnic.xyz`.
7. Verify Redis and MySQL counts on the VM.

Optional manual equivalent:

```bash
ssh -i ~/.ssh/oci_arm_key ubuntu@134.185.100.15 'cd ~/apps/axon && ./scripts/load-test/prepare-load-test-compose.sh 1000 1'
```

```bash
scp -i "$OCI_SSH_KEY" "$OCI_HOST:/home/ubuntu/apps/axon/scripts/load-test/jwt-tokens.json" "$AXON_REPO_ROOT/scripts/load-test/jwt-tokens.json"
```

```bash
cd "$AXON_REPO_ROOT" && FLOW=payment SCENARIO=spike MAX_VUS=100 USE_PRODUCTION_API=true USE_TOKEN_FILE=true TOKEN_FILE_PATH="$AXON_REPO_ROOT/scripts/load-test/jwt-tokens.json" ENTRY_SERVICE_URL="$PUBLIC_ENTRY_URL" CORE_SERVICE_URL="$PUBLIC_CORE_URL" ACTIVITY_ID=1 PRODUCT_ID=1 FCFS_LIMIT_COUNT=200 USER_ID_START=1000 USER_ID_END=1999 k6 run scripts/load-test/k6-fcfs-load-test.js
```

GitHub Actions path: useful for remote execution and artifact collection, but not the preferred official baseline while k6 runs inside the VM.

- Actions workflow: `Run VM Compose Baseline`
- Execution mode: GitHub Actions connects to the VM through SSH, but k6 runs inside the VM.
- Result retrieval: the workflow downloads `latest-compose-baseline.tar.gz` from the VM and uploads it as a GitHub Actions artifact named `vm-compose-baseline`.
- `use_resource_profile=true`: deploy with `compose.app.yml + infrastructure/compose/compose.resources.yml`.
- `use_resource_profile=false`: deploy with `compose.app.yml` only. Use this only for A/B checks against the older unlimited Compose shape.
- `redeploy=true`: pull `main`, rebuild/restart Compose, then run k6.
- `redeploy=false`: skip rebuild and run k6 against the currently running containers.
- `flow=behavior|reservation|payment|full`: choose which k6 path to execute.

Use `flow=full` only after the smaller flows are stable.

Manual VM fallback:

```bash
cd ~/apps/axon
docker compose -f compose.app.yml -f infrastructure/compose/compose.resources.yml up -d --build
docker restart axon-nginx
./scripts/load-test/run-baseline-compose.sh 1000 1
```

## Mandatory Nginx Restart After App Recreate

Status: active operational rule

When `axon-entry` or `axon-core` is recreated, restart `axon-nginx` before any load test that goes through `127.0.0.1:28080`, `https://axon.opicnic.xyz`, or the nginx container path.

Reason:

- Docker Compose can recreate `axon-entry` or `axon-core` with a new container IP.
- Nginx can keep using the stale upstream IP it resolved earlier.
- The app can be healthy on the direct published port while nginx still sends traffic to the old container IP.

Symptom:

```text
direct 127.0.0.1:8081 responds
nginx  127.0.0.1:28080 returns 502
nginx log: connect() failed (113: Host is unreachable) while connecting to upstream
```

Required post-recreate check:

```bash
cd ~/apps/axon
docker restart axon-nginx

curl -fsS http://127.0.0.1:8081/actuator/health
curl -sS -o /tmp/nginx-entry.out -w "nginx_entry_http=%{http_code} time=%{time_total}\n" \
  -X POST http://127.0.0.1:28080/api/v1/entries \
  -H "Content-Type: application/json" \
  -d "{}"
```

Expected unauthenticated probe result:

```text
nginx_entry_http=401
```

`401` means nginx reached Entry and Spring Security rejected the unauthenticated request. That is a valid path check. `502`, `499`, or `connect() failed ... upstream` means do not run the load test yet.

Warm-run before/after loop. Use this when comparing code or config changes and the first post-deploy cold run would hide the real signal:

> `MEASURED_NUM_USERS=3000` and `MEASURED_MAX_VUS=600` is a VU-capped shared-iteration throughput run. It processes 3,000 unique users through at most 600 active k6 VUs; it is not a 3,000-person simultaneous waiting-room flash crowd. Use `MAX_VUS=3000` when the test question is event-open concurrency.

```bash
cd ~/apps/axon

SCENARIO=waiting_burst \
FLOW=reservation \
WARMUP_NUM_USERS=50 \
WARMUP_MAX_VUS=5 \
WARMUP_FCFS_LIMIT_COUNT=50 \
MEASURED_NUM_USERS=3000 \
MEASURED_MAX_VUS=600 \
MEASURED_FCFS_LIMIT_COUNT=600 \
MEASURED_RUNS=2 \
K6_ENTRY_SERVICE_URL=http://127.0.0.1:28080 \
  ./scripts/load-test/run-warm-baseline-compose.sh 1
```

This wrapper intentionally runs a small real reservation-path warm-up, then runs the measured baseline after `run-baseline-compose.sh` resets the test data again. Treat the warm-up run as setup, not as a measured result.

Known-good validation:

```text
commit: be6975a
artifact: /home/ubuntu/apps/axon/artifacts/load-test/20260708-074127-warm-baseline
measured-1: status 0, success 600, error 0, reservation p95 127.10ms
measured-2: status 0, success 600, error 0, reservation p95 115.05ms
```

For `FLOW=reservation`, DB entries and purchases are not expected. The reservation-only domain target is Redis counter/users matching `FCFS_LIMIT_COUNT`.

Payment flow warm-run validation:

```bash
cd ~/apps/axon

SCENARIO=waiting_burst \
FLOW=payment \
WARMUP_NUM_USERS=50 \
WARMUP_MAX_VUS=5 \
WARMUP_FCFS_LIMIT_COUNT=50 \
MEASURED_NUM_USERS=3000 \
MEASURED_MAX_VUS=600 \
MEASURED_FCFS_LIMIT_COUNT=600 \
MEASURED_RUNS=1 \
K6_ENTRY_SERVICE_URL=http://127.0.0.1:28080 \
  ./scripts/load-test/run-warm-baseline-compose.sh 1
```

Known-good validation:

```text
commit: 93a29a5
artifact: /home/ubuntu/apps/axon/artifacts/load-test/20260708-payment-main-600
measured-1: status 0, success 600, error 0, reservation p95 1194.20ms, http p95 1621.06ms
domain: Redis 600/600, DB entries/purchases 600/600, convergence 0s/0s

artifact: /home/ubuntu/apps/axon/artifacts/load-test/20260709-payment-main-600-repeat
measured-1: status 0, success 600, error 0, reservation p95 613.05ms, http p95 799.01ms
domain: Redis 600/600, DB entries/purchases 600/600, convergence 0s/0s
```

For `FLOW=payment`, `check-results-compose.sh` prints DB entries/purchases convergence seconds. Use this as the first split between payment-path failure and Core async persistence delay.

Fast debug loop on the VM. Use this only to isolate obvious server-side failures before running the external Mac baseline:

```bash
cd ~/apps/axon

FLOW=behavior MAX_VUS=100 FCFS_LIMIT_COUNT=20 \
  ./scripts/load-test/run-baseline-compose.sh 100 1

FLOW=reservation MAX_VUS=100 FCFS_LIMIT_COUNT=20 \
  ./scripts/load-test/run-baseline-compose.sh 100 1

FLOW=payment MAX_VUS=100 FCFS_LIMIT_COUNT=20 \
  ./scripts/load-test/run-baseline-compose.sh 100 1

FLOW=full MAX_VUS=100 FCFS_LIMIT_COUNT=20 \
  ./scripts/load-test/run-baseline-compose.sh 100 1
```

Waiting-burst diagnostic loop on the VM. Use this to reproduce the FCFS event-open burst against the VM nginx path:

```bash
cd ~/apps/axon

SCENARIO=waiting_burst FLOW=payment NUM_USERS=3000 FCFS_LIMIT_COUNT=400 MAX_VUS=3000 K6_ENTRY_SERVICE_URL=http://127.0.0.1:28080 \
  ./scripts/load-test/run-baseline-compose.sh 3000 1

SCENARIO=waiting_burst FLOW=payment NUM_USERS=3000 FCFS_LIMIT_COUNT=500 MAX_VUS=3000 K6_ENTRY_SERVICE_URL=http://127.0.0.1:28080 \
  ./scripts/load-test/run-baseline-compose.sh 3000 1

SCENARIO=waiting_burst FLOW=payment NUM_USERS=3000 FCFS_LIMIT_COUNT=600 MAX_VUS=3000 K6_ENTRY_SERVICE_URL=http://127.0.0.1:28080 \
  ./scripts/load-test/run-baseline-compose.sh 3000 1
```

The runner also supports selecting the Docker network used by the k6 container:

- `K6_DOCKER_NETWORK=host`: k6 uses the VM host network.
- `K6_DOCKER_NETWORK=axon_axon-network`: k6 joins the Compose bridge network and can call service DNS names such as `entry-service`.

Use these only for diagnosis. Do not mix their numbers as one before/after result.

Nginx path, reservation-only:

```bash
cd ~/apps/axon

K6_DOCKER_NETWORK=host \
SCENARIO=waiting_burst \
FLOW=reservation \
NUM_USERS=3000 \
FCFS_LIMIT_COUNT=600 \
MAX_VUS=3000 \
K6_ENTRY_SERVICE_URL=http://127.0.0.1:28080 \
  ./scripts/load-test/run-baseline-compose.sh 3000 1
```

Host published-port path, payment flow:

```bash
cd ~/apps/axon

K6_DOCKER_NETWORK=host \
SCENARIO=waiting_burst \
FLOW=payment \
NUM_USERS=3000 \
FCFS_LIMIT_COUNT=500 \
MAX_VUS=3000 \
K6_ENTRY_SERVICE_URL=http://127.0.0.1:8081 \
K6_CORE_SERVICE_URL=http://127.0.0.1:8080 \
  ./scripts/load-test/run-baseline-compose.sh 3000 1
```

Docker bridge direct path, payment flow:

```bash
cd ~/apps/axon

K6_DOCKER_NETWORK=axon_axon-network \
SCENARIO=waiting_burst \
FLOW=payment \
NUM_USERS=3000 \
FCFS_LIMIT_COUNT=500 \
MAX_VUS=3000 \
K6_ENTRY_SERVICE_URL=http://entry-service:8081 \
K6_CORE_SERVICE_URL=http://core-service:8080 \
  ./scripts/load-test/run-baseline-compose.sh 3000 1
```

Interpretation boundary for the 2026-07-01 and 2026-07-02 VM diagnostic runs:

- `3000/400`: stable success baseline in latest repeats.
- `3000/500`: latency boundary candidate; domain consistency can succeed while `reservation_duration` p95 approaches or crosses 5s.
- `3000/600`: stress/failure reproduction candidate; repeated runs showed large variance, including `EOF`, nginx `500`, Redis success count `600`, and DB convergence around `588~590` in failure runs.
- `3000/500` through Docker bridge direct reached DB `500/500` in repeated payment-flow runs, but p95 still remained high.
- `3000/500` through the host published port showed high variance: one run could persist only a small subset, while later repeats reached `498~500/500`.
- `3000/600` reservation-only through nginx changed after connection tuning, but nginx `499` and high p95 still appeared around the burst boundary.

Do not treat one run as the capacity number. The useful signal is the boundary shape:

```text
400: stable success candidate
500: latency boundary candidate
600: unstable stress/failure reproduction candidate
```

`499` in the nginx access log means the client closed the connection before nginx returned a response. In this test, it usually means k6 timed out or disconnected while nginx was still waiting for the upstream path to finish.

Do not conclude from this evidence alone that nginx, Docker networking, or Entry application code is the sole root cause. Use OpenTelemetry/Jaeger and metrics to split:

- k6 connection wait
- nginx request time and upstream response time
- Docker host/bridge path
- Entry controller/service time
- Redis Lua latency
- reservation token handling
- Kafka publish latency
- Core consumer and DB persistence

When the 600 run fails, collect logs and resource snapshots before changing code:

```bash
docker logs --since=2m axon-nginx 2>&1 | tail -120
docker logs --since=2m axon-entry 2>&1 | tail -160
docker logs --since=2m axon-core 2>&1 | tail -160
docker stats --no-stream axon-nginx axon-entry axon-core axon-mysql axon-redis broker_1 kafka-controller
ss -s
```

If `entry-service:8081` direct traffic succeeds but `axon-nginx:80` traffic fails, verify nginx first:

```bash
docker exec axon-nginx nginx -T | grep -E 'worker_connections|multi_accept|upstream|keepalive|proxy_http_version|proxy_pass' -n
docker logs --since=2m axon-nginx 2>&1 | grep -Ei 'worker_connections|upstream|connect|timeout|reset|refused|failed|500'
```

After changing nginx connection settings, retest nginx-only reservation flow before returning to payment flow:

```bash
SCENARIO=waiting_burst FLOW=reservation NUM_USERS=3000 FCFS_LIMIT_COUNT=600 MAX_VUS=3000 K6_ENTRY_SERVICE_URL=http://127.0.0.1:28080 \
  ./scripts/load-test/run-baseline-compose.sh 3000 1
```

Flow boundary:

- `behavior`: sends `PAGE_VIEW` and `CLICK` behavior events only.
- `reservation`: executes FCFS reservation only and does not confirm payment.
- `payment`: executes reservation and payment prepare/confirm, without behavior events.
- `full`: executes behavior events, reservation, and payment.

Optional overrides:

```bash
MAX_VUS=3000 FCFS_LIMIT_COUNT=200 PRODUCT_ID=1 \
RESOURCE_PROFILE=infrastructure/compose/compose.resources.yml \
  ./scripts/load-test/run-baseline-compose.sh 3000 1
```

## What The Script Captures

The run creates:

- `artifacts/load-test/<run-id>-compose-baseline/k6-summary.json`
- `artifacts/load-test/<run-id>-compose-baseline/k6-console.log`
- `artifacts/load-test/<run-id>-compose-baseline/domain-check.log`
- `artifacts/load-test/<run-id>-compose-baseline/docker-stats.txt`
- `artifacts/load-test/<run-id>-compose-baseline/docker-stats-timeseries.txt`
- `artifacts/load-test/<run-id>-compose-baseline/docker-ps.txt`
- `artifacts/load-test/<run-id>-compose-baseline/ss-before.txt`
- `artifacts/load-test/<run-id>-compose-baseline/ss-after.txt`
- `artifacts/load-test/<run-id>-compose-baseline/<container>.log`
- `artifacts/load-test/<run-id>-compose-baseline/summary.md`
- `artifacts/load-test/<run-id>-compose-baseline/run-meta.txt`
- `artifacts/load-test/<run-id>-compose-baseline.tar.gz`
- `artifacts/load-test/latest-compose-baseline.tar.gz`
- `artifacts/load-test/latest-compose-baseline.txt`

`summary.md` is an automatically generated first-pass summary. It is not a replacement for engineering analysis; use it to decide which raw files to inspect first.

`run-meta.txt` records the effective route configuration, including `k6_docker_network`, `k6_entry_service_url`, and `k6_core_service_url`. Always check it before comparing two runs.

## Success Criteria

- k6 completes the spike scenario.
- `fcfs_error_count` is `0`.
- `fcfs_success_count` matches `FCFS_LIMIT_COUNT`.
- `interrupted_iterations` is `0`.
- Redis counter/set and DB entry count are checked after the run.
- For `FLOW=payment` and `FLOW=full`, DB entries and purchases match `FCFS_LIMIT_COUNT`.
- For `FLOW=reservation`, DB entries and purchases can remain `0` because payment confirmation and Core purchase persistence are intentionally skipped.
- The result directory contains enough evidence to compare against the next run on the same VM.

Notes:

- `http_req_failed` may be high when `410 Sold Out` is counted as an HTTP failure by k6. Treat the domain check `reservation valid business outcome (200/409/410)` as the main business-success indicator for the reservation-only flow.
- Reusing the same campaign activity after a successful run contaminates results because Redis/DB state already contains winners, retry tokens, and sold-out state. Reset seed data and regenerate tokens before every official run.

## Next Step After Baseline

After one clean baseline, attach OpenTelemetry/Jaeger for diagnosis mode. Do not compare OTel-attached latency directly against the baseline because the agent adds tracing overhead.

Measurement flow:

1. Run baseline with `compose.app.yml + infrastructure/compose/compose.resources.yml`, without OTel.
2. Attach OpenTelemetry/Jaeger only for diagnosis and trace inspection.
3. Apply one bottleneck fix.
4. Run final measurement again with `compose.app.yml + infrastructure/compose/compose.resources.yml`, without OTel.

OTel/Jaeger evidence can explain where time was spent. The headline performance number should come from the OTel-off baseline/final pair.

## 2026-07-15 Follow-up: Stable-Network Final Measurement (Pending)

**Status: pending.** This section is the next execution checklist after the
2026-07-15 external generator network investigation. It is intentionally
separate from the historical 600-VU records above.

### Final Scenario

```text
flow:          payment
shape:         waiting_burst
users:         3000
max VUs:       3000
FCFS limit:    800
VM profile:    Entry/Core/Axon nginx = 1.5 / 1.2 / 0.5 CPU
path:          Mac external direct origin -> host nginx -> axon-nginx -> Entry/Core
protocol:      3 full warm-ups, then 3 measured runs
```

The 2026-07-13 `3000 users / 3000 VUs / FCFS 800` successes are a provisional
historical reference, not the final current-code result. The current-code run
must be repeated from a stable home wired/Wi-Fi connection before T27 uses a
final p95 or RPS.

### Before Running

1. Record the generator network's unloaded and loaded latency/jitter using the
   same method for the entire measurement session. Do not use a café/shared
   Wi-Fi session with multi-second loaded latency as final evidence.
2. On the VM, record the source revision and resource profile, then rebuild and
   recreate the services once. Do not change code, CPU quotas, nginx settings,
   or network between the warm-up and measured runs.

```bash
cd ~/apps/axon
git rev-parse HEAD
grep -E '^(ENTRY_CPUS|CORE_CPUS|NGINX_CPUS)=' .env
docker compose -f compose.app.yml -f infrastructure/compose/compose.resources.yml \
  build entry-service core-service
docker compose -f compose.app.yml -f infrastructure/compose/compose.resources.yml \
  up -d --force-recreate entry-service core-service axon-nginx
```

3. Confirm Entry/Core health and retain the output with the result directory.

### Final Command

Run from the stable Mac network. `K6_EVENT_OUTPUT=true` is required once so the
artifact includes reservation ingress/completion peak data; do not print or
inspect per-request console logs unless the run fails.

```bash
cd "$AXON_REPO_ROOT"

RUN_ID="$(date '+%Y%m%d-%H%M%S')-home-final-payment-3000vu-800"
RESULT_ROOT="$PWD/artifacts/load-test/$RUN_ID"
mkdir -p "$RESULT_ROOT"

# Save the observed unloaded/loaded network values in this directory.
# Use the same network-quality tool/session for all three measured runs.

K6_EVENT_OUTPUT=true \
RUN_ID="$RUN_ID" \
RESULT_ROOT="$RESULT_ROOT" \
./scripts/load-test/run-external-warmed-payment.sh 1
```

### Acceptance Gate

Accept a final measurement only if **all three measured runs** meet every
condition below. Warm-up results are setup data and are never used as headline
numbers.

- `fcfs_success_count = 800`, `fcfs_error_count = 0`
- Redis counter/users = `800/800`
- DB entries/purchases = `800/800`; convergence seconds retained
- Host nginx reservation records = `3000`, status `200=800`, `410=2200`
- Axon nginx reservation completion records = `3000`, status `200=800`,
  `410=2200`
- No host nginx `worker_connections`, upstream timeout/connect/reset signal
- Same Git SHA, VM CPU profile, host nginx configuration, and generator
  network session across the three measured runs

Use the median of the three measured runs for each final p95/RPS value. Keep
the metrics separate:

- client E2E `reservation_duration` p95
- k6 `http_req_duration` p95
- host nginx request/upstream timing p95
- Axon nginx reservation completion peak RPS
- reservation ingress peak RPS (from the k6 event artifact)

### Home Measurement Checkpoints (Pending)

Default runtime conditions:

- VM: Entry `1.5 CPU`, Core `1.2 CPU`, Axon nginx `0.5 CPU`
- Axon nginx container: `worker_connections 2048`, `multi_accept` off
- External path only, host nginx: `worker_connections 4096`,
  `worker_rlimit_nofile 16384`; upstream keepalive is off (the `256` trial was
  reverted after Tomcat `400` responses)

For each row: fix the Git SHA and every condition other than the listed
comparison variable; run 3 warm-ups and 3 measured runs.

Historical change order: Entry CPU/cache/publish diagnosis and executor work
(2026-07-06 to 08) -> host nginx connection-limit fix (2026-07-10 05:07 UTC)
-> Axon nginx CPU follow-up (2026-07-13). The host nginx decision below is a
completed prerequisite, not a new A/B row.

| Checkpoint | Compare | Scenario / path |
|---|---|---|
| Entry CPU | `ENTRY_CPUS=0.6` vs `1.5` | reservation, 3000 users / 600 VUs / FCFS 600, Axon nginx path |
| Campaign meta preload | `PRELOAD_CAMPAIGN_META=false` vs `true` | reservation, 3000 users / 600 VUs / FCFS 600, Axon nginx path |
| Backend event executor | pre-executor Git checkpoint vs current | reservation, 3000 users / 600 VUs / FCFS 600, Axon nginx path |
| Axon nginx CPU | `NGINX_CPUS=0.1` vs `0.5` | payment, 2000 users / 2000 VUs / FCFS 500, Axon nginx path |
| Final event-open result | current code/config | payment, 3000 users / 3000 VUs / FCFS 800, Mac external direct origin |

Completed nginx decisions, retained as diagnosis history rather than new A/B tests:

| Layer | Decision | Evidence / disposition |
|---|---|---|
| Host nginx | `worker_connections 768 -> 4096`, `worker_rlimit_nofile 16384` | Error log recorded `768 worker_connections are not enough while connecting to upstream`. Keep the fixed limit; do not deliberately recreate the failing live configuration. |
| Axon nginx | `worker_connections 8192 + multi_accept` trial | Rejected: it increased burst pressure on Entry. Current tracked configuration remains `worker_connections 2048`. |
| Axon nginx | CPU quota `0.1 -> 0.5` | Reproducible A/B checkpoint above. |
| Host nginx -> Axon nginx | upstream `keepalive 256` | Rejected: this environment produced Tomcat `400 Bad Request` responses. Keep upstream keepalive off. |

### How to Use These Results

Do not present rows with different VU counts, FCFS limits, or request paths as
one before/after result. Use two separate claims:

1. **Cumulative improvement benchmark:** reproduce one fixed internal scenario
   (`reservation`, 3000 users / 600 VUs / FCFS 600) at a pre-improvement
   checkpoint and at the current checkpoint. This is the only result allowed
   to state cumulative p95, error-rate, or throughput improvement.
2. **Event-open capacity validation:** current code under `payment`, 3000
   users / 3000 VUs / FCFS 800. Report it as a final all-success capacity
   result, not as a percentage comparison with the first benchmark.

The individual rows above explain *why* the cumulative benchmark changed.
They are per-change evidence only and must retain their own scenario labels.

For the T27 diagnosis narrative, do not remeasure every historical experiment.
Use a number only when that intervention has a same-condition repeated A/B
result. Otherwise retain the direct evidence (for example, the host nginx
connection-saturation error), the decision, and its current configuration.
Remeasure an individual intervention only when it will be a portfolio headline.

After a passing final run, update T27 with the three-run median and link the
result directory. Move this section to a dated completed record or mark it
implemented; retain the 2026-07-15 boundary devlog as history.

## 2026-07-30 Core Kafka-Native Backlog A/B

Status: completed

This comparison answers a Core durability-boundary question. It is not a
headline ingress performance benchmark.

Fixed conditions:

- External Mac payment path
- 1,000 users / 1,000 VUs / FCFS 800
- Entry/Core/Axon nginx CPU: `1.5/1.2/0.5`
- Host nginx: `worker_connections 4096`,
  `worker_rlimit_nofile 16384`, no upstream `keepalive 256`, no host listen
  backlog override
- Axon nginx container: `worker_connections 2048`,
  `listen 80 backlog=4096`; this uncommitted VM setting was held constant and
  was not independently validated by this A/B

| Result | Before `49ca09f` | After `d5f396b` |
|---|---:|---:|
| Measured FCFS success/errors | `800/0`, `800/0` | `800/0`, `800/0` |
| DB Entry/Purchase | `800/800`, `800/800` | `800/800`, `800/800` |
| DB convergence | `0s`, `0s` | `0s`, `0s` |
| Hikari active scrape peak | `3` | `2` |
| Hikari pending/timeout | `0/0` | `0/0` |
| Reservation p95 | `3129ms`, `2723ms` | `4125ms`, `2547ms` |
| HTTP p95 | `1637ms`, `1701ms` | `1884ms`, `1904ms` |

The latency samples overlap and do not support a speedup claim. The accepted
result is that the same 800-result workload converged without application
queues or DB-pool pressure.

Crash injection:

- Core stopped: Kafka group lag `800`, DB `0/0`.
- Abrupt kill after partial work: DB `360/360`, committed offset progress
  `340`; 20 durable records remained uncommitted.
- Restart: Entry/Purchase `800/800`, distinct users `800/800`, group lag `0`.

Evidence:

- `artifacts/load-test/20260730-152209-queue-before-main49ca-controlled`
- `artifacts/load-test/20260730-153943-queue-after-d5f396b-controlled`
- `artifacts/load-test/20260730-queue-crash-injection-d5f396b/result.md`

## 2026-08-03 FCFS Ledger Transaction Regression

Status: completed

Purpose:

- Validate the Entry–Purchase single-transaction boundary after removing the
  former batch transaction-event/`REQUIRES_NEW` chain.
- Confirm that UserSummary projection failure history and Kafka offset
  boundaries do not cause loss or duplicate ledger rows.
- This is a correctness/capacity regression, not a latency improvement A/B.

Runtime:

- Code: `bb34774`
- Oracle VM Docker Compose
- Entry/Core/Axon nginx CPU: `1.5/1.2/0.5`
- External payment `waiting_burst`

### Repeated results

| Shape | Runs | FCFS success/errors | Entry/Purchase | DB convergence | reservation p95 | host request p95 | Axon completion peak |
|---|---:|---|---|---|---|---|---|
| 1,000 users / 1,000 VUs / FCFS 800 | 3 | all `800/0` | all `800/800` | all `0s` | `2.253s / 6.046s / 1.907s` | `1.664s / 5.240s / 1.306s` | `239 / 284 / 268 req/s` |
| 3,000 users / 3,000 VUs / FCFS 800 | 3 | all `800/0` | all `800/800` | all `0s` | `5.120s / 5.704s / 4.573s` | `1.777s / 2.510s / 1.311s` | `817 / 898 / 1,067 req/s` |

Final state after the repeated runs:

- broker committed lag: `0`
- command DLT end offset: `0`
- UserSummary projection-failure topic end offset: `0`
- Entry/Purchase pairs: `800`
- UserSummary timestamps matching durable Purchase timestamps: `800`
- Hikari active/pending/timeout at final snapshot: `0/0/0`

Latency boundary:

- Current host-to-Axon and Axon-to-Entry TCP connect p95 stayed in the
  single-digit millisecond range.
- Most server-side reservation time was upstream header/response wait inside
  the Entry path.
- The generator was on a non-final external network and the 1,000-VU second run
  was a large latency outlier. Do not use these p95 values as a portfolio
  speedup claim.
- The accepted result is three consecutive loss-free 3,000-VU/800
  convergences on the current code.

### Crash injection

- Core stopped: command lag `800`, DB Entry/Purchase `0/0`.
- Core started and was terminated with `SIGKILL` after the first ledger batch
  committed: Entry/Purchase `20/20`, committed offset progress `0`.
- Restart redelivered those 20 durable-but-uncommitted messages.
- Final Entry/Purchase, distinct users, ledger pairs, and matching UserSummary
  timestamps were all `800`; committed lag was `0`.
- Command DLT and projection-failure offsets remained `0`.

Evidence:

- `artifacts/load-test/20260803-fcfs-ledger-regression-1000vu-800`
- `artifacts/load-test/20260803-fcfs-ledger-regression-1000vu-800-r2`
- `artifacts/load-test/20260803-fcfs-ledger-regression-1000vu-800-r3`
- `artifacts/load-test/20260803-fcfs-ledger-3000vu-800-r1`
- `artifacts/load-test/20260803-fcfs-ledger-3000vu-800-r2`
- `artifacts/load-test/20260803-fcfs-ledger-3000vu-800-r3`
- `artifacts/load-test/20260803-fcfs-ledger-crash-recovery/result.md`
