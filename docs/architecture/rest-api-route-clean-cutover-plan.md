# REST API Route Clean Cutover Plan

> Status: active (implemented)
> Updated: 2026-07-14
> Scope: Thymeleaf SSR을 유지한 채 JSON API URL을 정리한다. 개인 프로젝트이므로 이전 URL alias/deprecation 기간 없이 한 번에 전환한다.

## Implementation Record

Completed on 2026-07-14 as a clean cutover.

- Core campaign, campaign activity, coupon API routes were moved to the target plural resource paths.
- Entry FCFS, coupon-entry, and behavior-event endpoints were moved to `/api/v1/**` and axon-nginx now owns only those explicit Entry routes.
- Core/Entry security matchers, Entry-to-Core metadata lookup, SSR controllers/templates, browser JS, active scripts, MockMvc tests, and the active runbook were updated together.
- The old `location /entry/` route and executable callers of old URLs were removed. No compatibility alias or redirect was added.
- `core-service` and `entry-service` test suites passed; the mounted nginx configuration passed `nginx -t` syntax validation.

The target routes below are the current contract. The `Current` column is retained only as migration history.

## 관리자 읽기 전용 데모 (2026-10-23)

- 쇼핑 화면의 **관리자 데모** 링크 또는 `GET /demo/admin`으로 OAuth 없이 진입한다.
- 30분짜리 `ROLE_DEMO` JWT를 별도 HttpOnly·SameSite=Lax 쿠키 `adminDemoToken`에
  저장한다. HTTPS에서는 Secure를 설정하며 기존 로그인·리프레시 쿠키는 덮어쓰지 않는다.
- 데모 쿠키가 있는 동안 Core는 데모 권한을 우선 사용한다. 명시적인 Authorization
  헤더는 기존과 같이 우선한다. `GET /demo/exit`는 데모 쿠키만 삭제하고 `/shop`으로 이동한다.
- 캠페인, 쿠폰, 행동 이벤트 정의와 성과 대시보드의 지정된 GET/HEAD 경로만 허용한다.
  데이터는 기존 조회 API를 사용하며 별도의 데모 DB나 데이터 생성은 하지 않는다.
- 고객별 정보, 참여자 목록, 대사, 운영 모니터링과 내부 API는 데모에서 공개하지 않는다.
  쓰기 메서드뿐 아니라 구매 완료처럼 부작용이 있는 GET 경로도 허용 목록 밖이다.
- 화면에는 읽기 전용 안내를 표시하고 생성·수정·발송과 AI 질의 UI를 숨긴다.
  실제 접근 제어는 Core의 `AdminDemoAccess` 허용 목록이 담당한다.
- 두 서비스가 JWT 인증을 공유하므로 Entry도 `ROLE_DEMO`를 거부한다. 데모 토큰을
  헤더나 기존 accessToken 쿠키로 옮겨도 구매·참여 API를 호출할 수 없다.
- `AdminDemoIntegrationTest`는 실제 JWT·보안 필터·SSR 화면과 조회 API, 변경 요청
  거부 및 쿠키 보존을 확인한다. Entry 차단은 `EntryApiContractTest`에서 확인한다.
- 2026-10-23 VM 배포 후 공개 브라우저와 실제 API의 읽기·쓰기 경계를 확인했다.
  배포 커밋·migration·확인 범위는 [운영 기록](../operations/oracle-compose-baseline-runbook.md#2026-10-23-관리자-데모-배포)을 따른다.

### 대시보드 조회 실패와 빈 화면

- 행동 통계 저장소 조회 실패는 실제 0건과 구분한다. 해당 방문·참여 수와 전환율은
  `null`, 조회에 실패한 시간대 그래프도 `null`로 반환한다. 화면은 `-`와 안내를 표시하고
  MySQL에서 조회한 구매 건수·매출은 계속 보여준다. 정상 조회 결과가 0건이면 0을 유지한다.
- 캠페인 기간 선택은 일반 조회와 SSE 모두 `period`를 전달한다. 기간을 바꾸면 이전
  스트림을 닫고 새 스트림을 열어 선택한 기간과 실시간 갱신 범위를 일치시킨다.
- 분석할 쿠폰 발송 이력이 없으면 증분 효과 화면에 빈 상태를 표시한다. 이벤트 목록도
  읽기 전용 데모에서 사용할 수 없는 생성 버튼을 안내하지 않는다.

## 일반 구매 확정 (2026-10-27)

일반 구매는 Core에서 처리하는 구매 체험 기능이다. 실제 PG 승인이나 배송을 수행하지 않는다.
Entry의 선착순 prepare/confirm과 별개이며, 이 변경이 결제 상태 영속화나 Outbox를 구현하지는 않는다.

| 경로 | 계약 |
|---|---|
| `POST /api/v1/shop-purchases` | 인증 사용자로 구매 확정. 입력은 UUID `requestId`, `productId`, 선택적 `userCouponId`이며 수량은 1이다. 가격과 사용자 ID는 요청으로 받지 않는다. |
| `GET /api/v1/shop-purchases/{purchaseId}` | 본인 소유의 일반 구매 조회. 타인 구매와 미존재는 동일한 404다. |
| `GET /payment/success?purchaseId=...` | 저장된 본인 구매의 결과 화면. 조회만 수행하며 과거 `productId/finalPrice/couponId` URL로 구매를 생성하지 않는다. |

최초 확정은 201, 동일 요청 재시도는 200이며 같은 구매 ID와 저장 금액을 반환한다.
같은 사용자·요청 ID로 상품이나 쿠폰을 바꾸면 409다. 쿠폰 미소유·조건 불일치는 400,
다른 구매에 사용한 쿠폰은 409, DB 일시 장애는 503으로 실패를 반환한다.
응답 필드는 `purchaseId`, `productId`, `quantity`, `paidAmount`, `status`다.

`shop_purchase_requests`의 사용자·요청 ID unique insert를 첫 쓰기로 수행한다.
요청 기록, 쿠폰 사용, 구매는 같은 트랜잭션에서 처리하며 실패 시 함께 롤백한다.
동시 중복 요청이 unique 제약에 걸리면 실패 트랜잭션 종료 후 새 조회 트랜잭션으로 기존
결과를 읽는다. 미완료 요청을 별도로 커밋하지 않으며 요청 기록은 자동 만료시키지 않는다.

상품 판매가와 쿠폰 할인은 서버에서 계산하고 최종 금액을 `DECIMAL(10,2)`에 맞춰 확정한다.
기존 정책대로 쿠폰 최소 금액과 정률 할인 기준은 상품 정상가이며, 적용 할인은 판매가를 넘지 않는다.
쿠폰 소유·기간·상태·카테고리는 행 잠금 아래에서 다시 확인한다.
브라우저는 응답 유실 시 같은 요청 ID와 입력을 보관해 재시도하고, 완료 후 새 구매는 사용자가
명시적으로 시작한다. 성공 화면 재조회는 쿠폰이나 구매를 다시 처리하지 않는다.

새 구매 POST에만 세션 CSRF 토큰을 요구한다. checkout은 토큰을 렌더링하고 JS가 헤더로 전송한다.
그 외 기존 API의 CSRF 정책과 쇼핑몰 GET 로그아웃 링크는 유지한다. 미인증 API는 401이며,
인증 검사보다 먼저 실행되는 CSRF 검사에 실패하면 403이다. 읽기 전용 데모는 구매를 할 수 없다.

배포 시 `scripts/migrations/2026-10-27-shop-purchase-requests.sql`의 테이블을 준비해야 한다.
2026-10-27 로컬 격리 MySQL에서 동시 중복·쿠폰 경쟁·트랜잭션 중간 실패 롤백을 확인했고,
실제 JWT 쿠키를 사용하는 MockMvc로 checkout 토큰과 POST/GET 경계, 데모 차단을 확인했다.
VM 배포와 제한된 운영 확인 범위는 [10월 27일 운영 기록](../operations/oracle-compose-baseline-runbook.md#2026-10-27-구매-확정과-쿠폰-처리-배포)을 따른다. 실제 PG 연동 결과가 아니다.

## Goal

- 화면을 렌더링하는 SSR route와 JSON을 반환하는 API route를 구분한다.
- JSON API는 `/api/v1` 아래의 복수형 resource 이름으로 통일한다.
- entry-service API도 `/entry/api/...` 같은 서비스 노출형 path 대신 `/api/v1/...`으로 노출한다.
- 모든 브라우저 호출, 서비스 간 호출, nginx, 보안 설정, 테스트, 부하/운영 스크립트를 같은 커밋에서 새 URL로 전환한다.

이 작업은 프론트엔드 재작성이나 API 응답 형식/비즈니스 로직 변경이 아니다. Thymeleaf SSR은 그대로 두고, 현재 API 계약의 **URL만 clean cutover** 한다.

## Non-goals

- React/Vue 등의 SPA 전환
- payment prepare/confirm 내부 계약 변경 (본 cutover 범위 제외)
- HTTP status, response body, DTO 필드의 일괄 REST 교정
- 이전 URL alias, redirect, version v2 추가
- controller/service/domain 구조 리팩터

`POST` 생성 응답을 모두 `201 Created`로 바꾸는 일도 이번 범위가 아니다. 현재 호출자와 테스트가 기대하는 status/body를 보존한다.

## Target Route Contract

### JSON API

| Current | Target | Owner / notes |
|---|---|---|
| `/api/v1/campaign` | `/api/v1/campaigns` | Core campaign CRUD, `exists` 포함 |
| `/api/v1/campaign/{campaignId}/activities` | `/api/v1/campaigns/{campaignId}/activities` | 특정 campaign의 activity collection |
| `/api/v1/campaign/activities` | `/api/v1/campaign-activities` | 전체 activity collection |
| `/api/v1/campaign/activities/count` | `/api/v1/campaign-activities/count` | 기존 count query 유지 |
| `/api/v1/campaign/activities/{id}` | `/api/v1/campaign-activities/{id}` | activity 단건 CRUD/status |
| `/api/coupons` | `/api/v1/coupons` | Core coupon CRUD |
| `/entry/api/v1/entries` | `/api/v1/entries` | Entry FCFS request. request body의 `campaignActivityId`는 유지 |
| `/entry/api/v1/entries/coupon` | `/api/v1/coupon-entries` | 기존 coupon issue entry와 구분 |
| `/entry/api/v1/behavior/events` | `/api/v1/behavior-events` | JS SDK behavior collector |
| `/entry/api/v1/behavior/events/diagnostics` | `/api/v1/behavior-events/diagnostics` | JS SDK diagnostic collector |

### SSR view routes

| Current | Target | Rendered view |
|---|---|---|
| `/mainshop` | `/shop` | `mainshop` |
| `/product/{id}` | `/products/{id}` | `product/detail` |
| `/campaign-activity/{id}` | `/campaign-activities/{id}` | `entry` |
| `/admin-create-campaign-activities` | `/admin/campaign-activities/new` | `admin_create_campaignActivitys` |
| `/` redirect target | `/shop` | root remains a redirect only |

Keep already clear SSR routes such as `/checkout`, `/events`, `/cart`, `/mypage`, `/admin/**`, `/payment/success` unchanged. Do not put SSR pages under `/api/v1`.

## Why Entry Uses `/api/v1/entries`

The FCFS request body already contains `campaignActivityId`, and `EntryRequestDto` is also used by test/support paths. Moving that ID into a path variable would force a DTO/use-case contract refactor unrelated to URL normalization. For this cutover, `POST /api/v1/entries` is a clear resource collection and preserves the existing request body and hot-path behavior.

`coupon-entries` remains a separate resource because the existing `/entries/coupon` invokes a different application-service flow. Do not merge the two flows just to make the URL look more generic.

## Required Implementation Work

### 1. Core JSON controller mappings and internal caller

- Change `CampaignController` to `/api/v1/campaigns`.
- Split `CampaignActivityController` mappings as in the table. A controller-level mapping may remain only if it keeps method routes unambiguous; otherwise use method-level full mappings. Do not create duplicate controllers.
- Change `CouponController` to `/api/v1/coupons`.
- Update `CampaignActivityMetaService#fetchCampaignActivity` to call `/api/v1/campaign-activities/{id}`.
- Update `core-service` SecurityConfig matcher paths from `/api/v1/campaign/**` to the new campaign and campaign-activity paths. Preserve the existing authentication policy; this is not an authorization redesign.

### 2. Entry controller, security, and nginx routing

- Change EntryController base mapping to `/api/v1/entries`; map coupon issue to `/api/v1/coupon-entries` without changing its service call.
- Change BehaviorEventController base mapping to `/api/v1/behavior-events`.
- Remove old `/entry/api/v1/**` matcher allowances from entry SecurityConfig after all callers move. Retain authentication/permit-all semantics for the new entry, behavior, diagnostics, test, and payment paths.
- Add explicit `axon-nginx` locations forwarding these entry-owned paths to `entry-service:8081`:
  - exact `/api/v1/entries`
  - exact `/api/v1/coupon-entries`
  - prefix `/api/v1/behavior-events/` and exact `/api/v1/behavior-events`
- Do not route all `/api/v1/**` to entry: core owns most of that namespace. Keep payment routing unchanged.
- Remove the old `location /entry/` only after `rg` confirms no live caller still uses it. This is a clean cutover, so no compatibility proxy is retained.

### 3. SSR controllers, templates, browser scripts

- Update StoreController and WebController mappings/redirects to the target SSR table.
- Update all Thymeleaf `href`, `th:href`, `onclick`, and plain links that point at old SSR routes.
- Update admin JS fetch URLs for campaign, campaign activity, and coupon APIs.
- Update behavior SDK configuration in `axon-tracker-config.js`, `layout/default.html`, `entry.html`, and campaign activity templates to the new behavior/entry API paths.
- Do not rename static asset paths such as `/image/product/**`; they are file assets, not product resource routes.
- Leave synthetic behavior-event `pageUrl` values and the dashboard's historical URL parsing unchanged in this task. Those strings are analytics data taxonomy, not HTTP route dispatch; migrate them separately only with a compatible query/data plan.

### 4. Test and operations callers

- Update MockMvc controller tests to assert only target URLs.
- Update k6, warm baseline, observability, live-recording, and seed scripts that call Entry/Behavior APIs.
- Update any request URL embedded in active docs/runbooks only when it is an executable command. Do not rewrite historical devlogs or legacy/reference documents; mark their commands historical if needed.

## Discovery Checklist Before Editing

Run this before and after the implementation, excluding build outputs:

```bash
rg -n -F \
  -e '/entry/api/v1' \
  -e '/api/coupons' \
  -e '/mainshop' \
  -e '/product/' \
  -e '/campaign-activity/' \
  -e '/admin-create-campaign-activities' \
  --glob '!**/build/**' --glob '!**/.gradle/**' .

rg -n '/api/v1/campaign([/"?]|$)' \
  --glob '!**/build/**' --glob '!**/.gradle/**' .
```

Before merge, old URLs may remain only in legacy/reference/history documents or in deliberately unchanged synthetic behavior-event URLs. Neither is an executable HTTP caller.

## Verification

1. Build/test relevant modules:

```bash
cd services/core-service && ./gradlew test
cd ../entry-service && ./gradlew test
```

2. Verify route ownership with the Compose nginx configuration:

```bash
docker compose -f compose.app.yml config
```

Confirm new Entry/Behavior paths proxy to entry-service and campaign/coupon paths continue to core-service.

3. Smoke checks in a running local Compose stack:

- `GET /shop` returns the main shop page.
- `GET /products/{id}` returns the product page.
- `GET /api/v1/campaigns` and `GET /api/v1/campaign-activities` reach Core.
- `POST /api/v1/behavior-events` reaches Entry and returns the existing accepted response.
- authenticated `POST /api/v1/entries` reaches Entry.

4. Final search shows no old executable caller or active controller/security/nginx mapping. Do not add aliases to make this search pass.

## Acceptance Criteria

- No controller exposes the old routes from the target table.
- All current templates, static JS, active scripts, service-to-service calls, tests, security matchers, and nginx locations use the target routes.
- Response bodies, status codes, and business-service calls stay unchanged.
- FCFS and behavior requests are still routed to entry-service through axon-nginx.
- Core campaign/activity/coupon APIs and SSR page navigation pass existing tests and smoke checks.
- No compatibility alias, redirect, or duplicate mapping is introduced for old API URLs.

## Design Decision: REST/RPC Mix

This is not a pure REST-only rewrite.

- CRUD and collection queries use resource-oriented routes: `campaigns`, `campaign-activities`, `coupons`.
- Participation/request/event creation uses resource-like collections: `entries`, `coupon-entries`, `behavior-events`.
- Explicit state-transition commands remain RPC-style where that is clearer: the existing payment `prepare` and `confirm` routes were intentionally preserved under the payment ownership boundary.

The decision rule is domain meaning, not a ban on URI verbs. Future command endpoints may use RPC-style action routes when they do not naturally create or update a resource.

## Review Checklist

- Is every changed line directly tied to a route migration?
- Did the implementation avoid changing payment internals, DTO shape, service logic, Kafka flow, and response semantics?
- Did it update the internal `CampaignActivityMetaService` HTTP URL, not just browser calls?
- Did nginx use narrow explicit Entry locations instead of capturing all `/api/v1/**`?
- Did the final search distinguish active code/scripts from historical docs?
