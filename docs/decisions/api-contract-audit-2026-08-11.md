# API 계약 감사 — 2026-08-11

상태: active (감사 완료, Security·행동 identity·이미지 업로드·대표 HTTP 계약 구현 완료)

아래 P0/P1/P2의 `현재`는 **감사 당시 상태**다. 이후 반영 여부는 바로 아래 구현 상태 표를 기준으로 판단한다.

## 결론

URL 모양보다 **누가 호출할 수 있는지, 언제 성공으로 확정하는지, 실패를 어떤 상태로 돌려주는지**에 실제 문제가 있다. 현재 CI는 정상 동작하지만 controller 테스트 대부분이 Security와 전역 예외 처리를 우회하므로 아래 문제를 잡지 못했다.

| 우선순위 | 의미 | 발견 수 |
| --- | --- | --- |
| P0 | 현재 기능 단절 또는 권한·데이터 신뢰 경계 위반 | 4 |
| P1 | 잘못된 성공·실패 의미 또는 운영 장애 가능성 | 6 |
| P2 | 일관성·가독성 문제, 단독으로 장애를 만들 가능성은 낮음 | 4 |

## 구현 상태

| 항목 | 상태 | 현재 계약 |
| --- | --- | --- |
| P0-1 결제 화면/API 불일치 | 미해결 · 결제 담당 범위 | Entry `prepare → confirm` 계약 합의 뒤 수정 |
| P0-2 포괄적 `permitAll` | **해결** | 익명은 `GET /api/v1/events/active` 등 명시 경로만, 나머지 API는 인증 |
| P0-3 USER/ADMIN 미분리 | **해결** | 운영 화면·명령·조회는 ADMIN, validation은 인증 사용자, Entry 메타 조회는 SYSTEM |
| P0-4 행동 이벤트 identity | **해결** | 로그인 userId는 JWT에서만 결정, 익명 이벤트는 필수 sessionId와 `userId=null` 사용 |
| P1-9 파일 업로드 | **해결** | ADMIN 전용, 5MB·25MP 제한, 실제 PNG/JPEG decode 후 서버 확장자로 저장 |
| P1-5 오류 상태 구분 | **부분 해결** | CampaignActivity·Event·Coupon의 미존재/잘못된 요청/상태 충돌을 404·400·409로 구분. Campaign·Product는 후속 범위 |
| P1-8 입력 검증 | **부분 해결** | CampaignActivity의 수치·기간과 Coupon 업무 규칙을 HTTP 400으로 거절. Payment·Dashboard는 후속 범위 |
| P1-10 실제 HTTP 계약 테스트 | **부분 해결** | Entry·BehaviorEvent·CampaignActivity·Event·Coupon 대표 계약을 실제 Security·MVC 체인으로 검증 |
| P2-1 생성 응답 | **부분 해결** | CampaignActivity·Event·Coupon 생성은 201과 `Location` 반환. Campaign 생성은 유지 |
| P2-2 Coupon 삭제 응답 | **해결** | 성공 시 204 No Content 반환 |

관리자는 `AXON_ADMIN_EMAILS`에 지정한 이메일과 정확히 일치하는 OAuth 사용자를 로그인 시 승격한다. 설정에서 이메일을 지워도 기존 DB의 ADMIN을 자동 강등하지 않는다. 배포 전 운영자 이메일을 환경 변수에 넣어야 신규 환경에서 관리자 화면이 잠기지 않는다.

## P0 — 먼저 닫아야 할 문제

### 1. 브라우저 결제 흐름이 현재 API와 연결되지 않는다

- **현재:** 화면은 `POST /core/api/v1/payments/process`를 호출한다. 이 endpoint는 과거 Core 결제 구조를 제거하면서 함께 삭제됐다. 현재 구현은 Entry의 `POST /api/v1/payments/prepare` → `POST /api/v1/payments/confirm` 2단계다.
- **문제:** 브라우저에서 결제 버튼을 누르면 존재하지 않는 API를 호출한다. `/confirm`으로 URL만 바꿔도 안 되며, 먼저 `/prepare`에서 approval token을 받아야 한다.
- **권장:** 결제 담당자와 2단계 계약을 확인한 뒤 화면을 `prepare → mock PG 성공 → confirm` 순서로 연결하고, 각 실패·만료·중복 확인을 통합 테스트로 고정한다.

### 2. 포괄적인 permitAll 때문에 내부·관리 API가 열린다

- **현재:** Core Security의 마지막 `/api/v1/** permitAll` 규칙으로 Event 변경 API, LLM query, validation API가 공개된다. Dashboard 조회·SSE도 관리자 화면과 달리 공개되어 있다.
- **문제:** 익명 사용자가 Event 정의를 변경하거나 Gemini 비용을 발생시킬 수 있다. validation은 공개되어 있으면서 principal을 바로 읽어 익명 요청에서 500이 날 수 있다.
- **권장:** 공개 API를 좁게 열거하고 나머지는 기본 인증으로 닫는다. Event active 조회만 공개하고 Event 변경, LLM query, validation은 인증 대상으로 분리한다.

### 3. 로그인한 일반 사용자가 관리자 변경 API를 호출할 수 있다

- **현재:** 캠페인·액티비티·쿠폰·파일·모니터링·대사 API와 `/admin/**`는 `authenticated`만 확인한다. OAuth 가입자는 기본 `ROLE_USER`이고 ADMIN 검사 코드는 주석 처리돼 있다.
- **문제:** 쇼핑몰 일반 사용자와 운영자 권한이 구분되지 않는다.
- **권장:** 공개 조회, 로그인 사용자 행동, 운영자 명령을 구분하고 운영자 화면·변경 API에는 `ROLE_ADMIN`을 요구한다. URL 규칙을 실제 Security 통합 테스트로 검증한다.

### 4. 행동 수집 API가 클라이언트의 userId를 신뢰한다

- **현재:** 행동 수집은 공개 API이고, 인증 정보가 없으면 request body의 `userId`를 사용한다. 현재 JS SDK는 화면에 렌더링된 userId를 보내지만 Authorization header는 구성하지 않는다.
- **문제:** 누구나 다른 userId로 행동을 적재할 수 있다. 이 데이터는 대시보드에 그치지 않고 행동 조건 기반 쿠폰·Webhook 발행에도 사용된다.
- **권장:** 인증 사용자의 userId는 JWT에서만 결정한다. 익명 방문은 별도 anonymous/session 식별자로 저장하고 숫자 userId로 승격하지 않는다. SDK도 토큰 전달 또는 same-origin cookie 인증 중 하나로 계약을 통일한다.

## P1 — 계약을 바로잡아야 할 문제

### 5. 오류가 HTTP 500으로 뭉개지고 응답 형식이 제각각이다

- Campaign·Coupon·Event·Product의 미존재가 주로 `IllegalArgumentException`이라 404가 아니라 500이 된다.
- 잘못된 상태 전이는 `IllegalStateException`이라 409가 아니라 500이 된다.
- 전역 handler는 CampaignActivity 미존재 한 종류만 다루며, Core·Entry·Behavior API의 오류 body가 서로 다르다.
- **권장:** not-found, invalid-request, conflict를 구분하고 하나의 `ProblemDetail` 기반 형식으로 매핑한다. 예상하지 못한 500에는 내부 예외 메시지를 노출하지 않는다.

### 6. Kafka 접수와 업무 완료가 같은 성공으로 표현된다

- **현재:** 쿠폰 발급과 결제 confirm은 broker ACK 뒤 200과 성공 body를 반환하지만, UserCoupon·Entry·Purchase DB 반영은 Core consumer에서 나중에 일어난다.
- **문제:** “명령 접수”와 “발급·구매 완료”가 API에서 구분되지 않는다. 후단 DLT 발생을 사용자가 확인할 방법도 없다.
- **권장:** 최소 계약은 202 Accepted와 `ACCEPTED` 상태다. 실제 완료까지 보장하려면 command/request ID와 상태 조회가 추가로 필요하다. 이 결정은 결제 담당 범위와 함께 확정한다.

### 7. 결제 confirm 재시도가 같은 결과를 돌려주지 않는다

- **현재:** Kafka ACK 뒤 1·2차 토큰을 삭제한다. 서버가 처리했지만 응답이 유실되면 같은 요청의 재시도는 410을 받는다.
- **문제:** 구매 명령은 DB 업무키로 중복을 수렴시키더라도, 사용자에게는 첫 요청이 성공했는지 확인할 수 없는 모호한 결과가 남는다.
- **권장:** confirm에 멱등키 또는 결제 시도 ID를 두고, 처리 결과를 일정 기간 저장해 동일 요청에는 같은 결과를 반환한다.

### 8. 입력 검증이 controller·service·DB에 흩어져 있다

- Payment와 Dashboard query에는 `@Valid`와 필수 문자열 검증이 없다.
- CampaignActivity는 음수 가격·수량·예산과 `end <= start`를 막지 않는다.
- Coupon은 service에서 검증하지만 예외가 400으로 매핑되지 않는다.
- Campaign의 `@Future startAt`은 시작된 캠페인의 PUT 수정까지 막을 수 있지만, 생성·수정 정책이 구분돼 있지 않다.
- **권장:** 필드 형식은 DTO, 교차 필드와 상태 정책은 application/domain에서 검증하고 각각 400·409로 매핑한다. 생성·수정 DTO는 정책이 다르면 분리한다.

### 9. 파일 업로드가 같은 origin에 임의 확장자로 공개된다

- **현재:** 인증 사용자라면 확장자와 실제 content를 검사하지 않고 업로드할 수 있고 `/uploads/**`에서 공개된다.
- **문제:** HTML·SVG 같은 실행 가능한 파일을 올리면 저장형 XSS와 토큰 탈취 경로가 될 수 있다. 일반 USER도 업로드 가능한 권한 문제와 결합된다.
- **권장:** ADMIN 전용으로 제한하고 허용 MIME·확장자·크기·실제 이미지 decode를 검증한다. 업로드 파일을 실행 가능한 same-origin 정적 리소스로 직접 제공하지 않는다.

### 10. API 계약 테스트가 실제 필터 체인을 검증하지 않는다

- **현재:** Core controller 테스트는 `standaloneSetup`, Entry 테스트는 controller 직접 호출 방식이다. 인증·인가, Bean Validation, 전역 예외 handler를 통과하지 않는다.
- **문제:** CI가 성공해도 공개되어서는 안 되는 API, 401/403 혼동, 404 대신 500, 잘못된 status를 발견하지 못한다.
- **권장:** 모든 메서드 테스트를 늘리기보다 대표 계약을 `MockMvc` 통합 테스트로 고정한다: public GET, USER 명령, ADMIN 명령, validation 실패, not-found, conflict, async accepted.

## P2 — 다음 정리에서 맞출 항목

1. Campaign·Activity·Event·Coupon 생성은 200 대신 201이 더 정확하다. 가능하면 `Location`도 제공한다.
2. Coupon 삭제는 빈 200 대신 204로 다른 삭제 API와 통일한다.
3. Cohort GET은 작업을 새로 접수하지 않으면서 결과가 없을 때 202를 반환한다. 404·204 또는 `PENDING` 표현 중 실제 의미를 정해야 한다.
4. Entry 성공 주석과 테스트 이름은 202라고 하지만 코드는 200이다. `PaymentConfirmationResponse`를 예약·쿠폰 응답에도 재사용해 용어가 실제 유스케이스와 어긋난다.

## 권한 계약 초안

| API 영역 | 권장 호출자 |
| --- | --- |
| 상품·진행 중 이벤트 조회 | 공개 |
| 행동 이벤트 수집 | 공개 가능, 단 인증 userId는 서버에서 결정 |
| 선착순 예약·쿠폰 요청·결제 | 로그인 USER |
| 캠페인·액티비티·쿠폰·Event 정의 변경 | ADMIN |
| Dashboard·LLM·대사·모니터링 | ADMIN |
| Entry→Core validation | 인증된 사용자 위임 호출 또는 별도 내부 서비스 인증 |

## 수정 순서

1. Security allowlist와 ADMIN 경계 + 보안 통합 테스트
2. 행동 이벤트 identity 계약 + 파일 업로드 제한
3. 공통 오류 형식과 404·400·409 매핑
4. DTO·교차 필드 validation
5. 결제 화면 연결, 비동기 202 의미, confirm 멱등성 — 결제 담당자와 합의 후
6. 마지막에 201·204와 DTO 이름 정리

한 번에 모든 응답을 바꾸지 않는다. 각 단계에서 현재 Thymeleaf/JS 호출자와 테스트를 함께 변경한다.

## 감사 범위와 증거

- Core·Entry controller, request DTO, SecurityConfig, 전역 exception handler
- Controller→service 예외 흐름과 Kafka ACK 이후 처리 경계
- Thymeleaf·JS fetch 호출, axon-nginx route, active flow·route 문서
- 현재 controller/security 테스트와 GitHub Actions CI

최초 감사 시점에는 API 동작 코드를 변경하지 않았다. 이후 1단계로 Core allowlist·ADMIN/SYSTEM 경계와 실제 Security filter-chain 테스트를 구현했다. 결제 내부는 다른 팀원 담당 범위라 문제를 기록만 했다.

### 1단계 검증

- `SecurityConfigTest`: 익명 401, USER 403, ADMIN 허용, SYSTEM 메타 조회 한정, 공개 Event 정의 조회를 실제 filter chain으로 검증
- `CustomOAuth2UserServiceTest`: 신규·기존 USER 승격, 이메일 정규화, 기존 ADMIN 비강등 검증
- `core-service ./gradlew --no-daemon test --rerun-tasks`: 통과 (2026-08-11)

### 2단계 검증

- `BehaviorEventControllerTest`: body `userId` 위조 무시, JWT userId 우선, 익명 sessionId 필수 계약 검증
- `ImageStorageServiceTest`: 실행 가능한 SVG/HTML, 손상 이미지, 5MB 초과 파일 거절과 서버 확장자 재결정 검증
- `FileControllerTest`, `SecurityConfigTest`: 잘못된 이미지 400과 USER 403/ADMIN 허용 검증

### 3단계: 대표 HTTP 계약과 실패 시나리오

전체 controller를 같은 깊이로 복제하지 않고, 프로젝트의 두 축인 Entry·BehaviorEvent와 후속 동작을 정의하는 CampaignActivity·Event·Coupon을 대표 계약으로 선택했다.

| 경로 | 고정한 계약 |
| --- | --- |
| Entry `POST /api/v1/entries` | 익명 401, Bean Validation 400, 성공 200, 미존재 404, 중복 409, 매진 410, 예상 밖 실패 500 |
| Entry `POST /api/v1/behavior-events` | 익명 session 필수, body userId 무시, 인증 identity 우선, validation 실패 400, 접수 202 |
| Core `POST /api/v1/campaigns/{id}/activities` | 익명 401, USER 403, ADMIN 생성 201 + `Location`, 입력 실패 400, campaign 미존재 404 |
| Core `PATCH /api/v1/campaign-activities/{id}/status` | 허용되지 않은 상태 전이 409 |
| Core Event/Coupon 관리 | active Event 공개 조회, USER 변경 차단, ADMIN 생성 201, 잘못된 정의 400, 미존재 404, Coupon 삭제 204 |

- Entry는 인증 실패 응답이 403으로 나오던 설정을 확인하고, 미인증 API 호출은 401을 반환하도록 수정했다.
- CampaignActivity는 일반 `IllegalArgumentException`·`IllegalStateException` 대신 미존재와 업무 충돌을 구분해 404·409로 매핑했다.
- `CampaignActivityRequest`는 음수 가격·수량·예산, 상품 액티비티의 0 수량 및 `endDate <= startDate`를 controller 진입 전에 거절한다. 쿠폰 액티비티의 수량 0은 허용한다.
- Event와 Coupon은 클라이언트 입력 오류와 리소스 미존재를 별도 예외로 구분해 400·404로 반환한다.
- HTTP → service → MySQL 저장을 통과하는 Testcontainers 테스트 4건을 추가했다: 정상 저장, validation 실패 무저장, 미존재 campaign 무저장, 상태 전이 거절 후 기존 상태 유지.
- CI는 FCFS Kafka→MySQL, CampaignActivity HTTP→MySQL, Entry Redis 통합 테스트 보고서가 없거나 skip되면 실패한다.

감사 직후 로컬 검증은 Entry 47 tests(3 skipped), Core 184 tests(25 skipped), failures/errors 0이었다. 로컬 Docker 부재로 skip된 HTTP→MySQL/Redis 대상은 2026-08-12 GitHub Actions에서 실제 실행했다. Core·Entry 전체 테스트와 필수 Testcontainers suite의 skip 방지 검사, Compose 검증, 이미지 빌드까지 [CI run 31561058964](https://github.com/NileTheKing/marketing-intelligence-platform/actions/runs/31561058964)에서 통과했다.

JaCoCo는 가능한 업무 경우의 수가 아니라 실행된 line·branch 비율로만 사용했다. 이번 변경 전→후는 Entry line 40.5→42.6%, branch 33.4→36.6%, Core line 39.9→40.5%, branch 33.5→33.7%다. 전체 비율을 목표로 테스트를 양산하지 않고 위 실패 계약을 우선했다.
