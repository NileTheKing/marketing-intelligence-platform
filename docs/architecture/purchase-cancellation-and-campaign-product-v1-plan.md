# Purchase Cancellation and Campaign-Only Product V1 Plan

Status: `active (implemented)`

## Goal

Keep FCFS inventory isolated from normal shop inventory, then make a confirmed purchase
cancellable without corrupting revenue, conversion, stock projection, or user summary.

```text
Campaign-only product
  -> FCFS reservation / payment
  -> Purchase(CONFIRMED)
  -> cancellation command
  -> Purchase(CANCELLED or REFUNDED)
  -> stock, UserSummary, dashboard projections corrected
```

This is not a general inventory-allocation system and does not reopen an FCFS slot after
post-payment cancellation.

## Facts in Current Code

- `Product` has one `stock` field and is visible from the normal shop path.
- A `CampaignActivity` can currently reference any product; active activities may share one product.
- FCFS admission uses Entry Redis counter/user-set keys.
- Core creates immutable-looking `Purchase` rows with no payment status.
- `CampaignStockSyncService` compares Redis and MySQL counts, then applies only positive MySQL purchase deltas to `Product.stock`.
- Dashboard `VISIT`, `ENGAGE`, `QUALIFY`, and `PURCHASE` currently all come from Elasticsearch behavior-event counts. Therefore its purchase count, GMV, AOV, conversion rate, and ROAS cannot exclude a future cancellation.

## Decisions

### 1. Campaign-only product, not generic inventory allocation

Add `products.campaign_only` (`boolean`, default `false`). A campaign-only product is not sellable
through the normal shop flow and may be assigned to at most one `ACTIVE` FCFS activity.

This deliberately avoids `inventory_allocations` / `inventory_reservations` tables. Those are needed
only when a normal shop and one or more campaigns must share the same physical inventory.

### 2. Purchase is the durable transaction fact

Add `PurchaseStatus`:

```text
CONFIRMED -> CANCELLED
CONFIRMED -> REFUNDED
```

Existing rows migrate to `CONFIRMED`. Do not delete a cancelled purchase row.

### 3. FCFS admission is not reopened after a completed-payment cancellation

Keep the Entry Redis counter and participant set unchanged after `Purchase` cancellation.
This intentionally trades a possible unused campaign slot for fairness and avoids reopening a closed
FCFS race. Reservation-token expiry before payment continues to release the Redis slot as today.

### 4. Final commercial metrics read MySQL, behavior steps read Elasticsearch

```text
ES:    VISIT, ENGAGE, QUALIFY and immutable behavior history
MySQL: confirmed purchase count, GMV, AOV, conversion rate, ROAS
```

An old ES `PURCHASE` event remains an audit/behavior fact; it is no longer the source of final
commercial metrics. V1 does not add a cancellation event to Elasticsearch.

## Implementation Plan

### A. Campaign-only product policy

1. Add `campaignOnly` to `Product` and an additive migration with default `false`.
2. In `CampaignActivityService`, when creating or activating a `FIRST_COME_FIRST_SERVE` activity with a product:
   - require `product.campaignOnly == true`;
   - reject if another `ACTIVE` activity already owns the same product.
3. Add a repository existence query for an active activity by product ID, excluding the current activity on update.
4. In every normal shop purchase entry point, reject `campaignOnly` products in the application service, not only in the page/UI.
5. Exclude campaign-only products from normal shop product listing and direct checkout/detail access.

V1 boundary: a product may be reused only after its previous FCFS activity is `ENDED`.

### B. Purchase cancellation state transition

1. Add `PurchaseStatus`, `status`, `cancelledAt`, and optional cancellation reason/reference fields to `Purchase`.
2. Add domain methods that allow only `CONFIRMED -> CANCELLED|REFUNDED`; repeating the same cancellation request is idempotent.
3. Expose a Core application command such as `cancelConfirmedPurchase(purchaseId, reason, occurredAt)`.
   - V1 can be an internal/admin command.
   - External PG refund/cancellation integration is owned by the payment module and must invoke this command only after its refund succeeds.
4. Keep `CampaignActivityEntry` as the historical FCFS approval. Do not delete it and do not alter Entry Redis admission state.

### C. Correct projections from confirmed purchases

1. Change campaign stock sync to count only `CONFIRMED` campaign purchases.
2. Support both positive and negative stock deltas:
   - confirmed purchase: decrease derived `Product.stock`;
   - cancelled/refunded purchase: restore it.
3. Rebuild the affected user's `UserSummary` from confirmed Purchase rows after cancellation. Do not blindly decrement fields because a cancelled latest purchase may change `lastPurchaseAt`.
4. Make RFM aggregation and cohort/LTV/repeat-purchase queries read only `CONFIRMED` purchases.

### D. Dashboard source correction

1. Add MySQL aggregate queries for one activity and a grouped query for campaign dashboards:
   `COUNT(*)`, `SUM(price * quantity)` filtered by activity/campaign, period, and `status=CONFIRMED`.
2. In `DashboardService`:
   - retain ES counts for `VISIT`, `ENGAGE`, and `QUALIFY`;
   - replace `PURCHASE` step with MySQL confirmed-purchase count;
   - calculate GMV/AOV/conversion/ROAS from that aggregate.
3. Update dashboard and LLM text from “purchase event count” to “confirmed purchase count”.
4. Use `EXPLAIN ANALYZE` on the new activity/status/period query. Add
   `(campaign_activity_id, status, purchase_at)` only if the existing index does not serve the observed query shape adequately.

## Data Migration

1. `products.campaign_only NOT NULL DEFAULT false`
2. `purchases.status NOT NULL DEFAULT 'CONFIRMED'`
3. nullable cancellation metadata fields
4. Backfill existing rows as `CONFIRMED`

Run migrations before deploying code that reads the new columns. Existing deployments use
`ddl-auto: update`, but explicit migration remains the reproducible VM cutover record.

## Tests

1. Normal shop rejects a campaign-only product server-side; it is absent from normal listing.
2. FCFS activation rejects a non-campaign-only product and a second active activity using the same product.
3. `CONFIRMED -> CANCELLED` succeeds once; duplicate cancellation is idempotent; invalid transitions fail.
4. Cancellation keeps Entry/Redis FCFS admission unchanged.
5. Stock sync restores one quantity when confirmed count decreases.
6. UserSummary rebuild correctly handles cancellation of the latest and a non-latest purchase.
7. Dashboard purchase count/GMV excludes cancelled/refunded purchases while ES visit/engage/qualify counts remain unchanged.
8. Existing campaign purchase, RFM, cohort, and Kafka pipeline tests remain green.

## Completion Criteria

- A campaign-only product cannot enter the normal shop purchase path or two simultaneous FCFS activities.
- Cancelling a confirmed campaign purchase preserves the Entry record and FCFS slot, restores derived product stock, and removes that purchase from MySQL-backed conversion/GMV metrics.
- No dashboard KPI derives final revenue from an Elasticsearch `PURCHASE` event.

## Implementation Record (2026-07-28)

- Added `Product.campaignOnly` and server-side normal-shop exclusion.
- Active FCFS activity creation/activation now requires a campaign-only product and rejects another active FCFS owner.
- Added `PurchaseStatus` (`CONFIRMED`, `CANCELLED`, `REFUNDED`) and internal cancellation/refund commands.
- Campaign stock, RFM, cohort and dashboard purchase aggregates now read confirmed purchases only.
- Dashboard keeps ES for behavior steps and uses MySQL confirmed `Purchase` aggregates for purchase count, GMV, AOV, conversion rate and ROAS.
- Added migration: `scripts/migrations/2026-07-28-add-purchase-status-and-campaign-only-product.sql`.
- Direct PG cancellation/refund wiring remains outside this repository's payment-module scope.

## Explicit Non-Goals

- No FCFS waitlist or cancellation-slot reopening.
- No multi-channel shared inventory allocation tables.
- No direct PG refund integration in Core.
- No Elasticsearch cancellation projection in V1.
