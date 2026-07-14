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

## Goal

- 화면을 렌더링하는 SSR route와 JSON을 반환하는 API route를 구분한다.
- JSON API는 `/api/v1` 아래의 복수형 resource 이름으로 통일한다.
- entry-service API도 `/entry/api/...` 같은 서비스 노출형 path 대신 `/api/v1/...`으로 노출한다.
- 모든 브라우저 호출, 서비스 간 호출, nginx, 보안 설정, 테스트, 부하/운영 스크립트를 같은 커밋에서 새 URL로 전환한다.

이 작업은 프론트엔드 재작성이나 API 응답 형식/비즈니스 로직 변경이 아니다. Thymeleaf SSR은 그대로 두고, 현재 API 계약의 **URL만 clean cutover** 한다.

## Non-goals

- React/Vue 등의 SPA 전환
- payment prepare/confirm 내부 계약 변경 (다른 개발자 담당 경계)
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
cd core-service && ./gradlew test
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
