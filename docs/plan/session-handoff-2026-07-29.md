# Session Handoff - 2026-07-29

Status: active

## Start Here

Read these documents before making a new change:

1. `AGENTS.md`
2. `docs/architecture-map.md`
3. `docs/plan/document-map.md`
4. This handoff

Code is authoritative when it conflicts with a document.

## Commits Completed In This Session

| Commit | Scope |
|---|---|
| `4fd4fd7` | Purchase persistence is separated from UserSummary projection and approval-event publishing. A projection failure no longer causes an already persisted Purchase to be replayed. |
| `bfd5fbf` | Campaign-only product policy, confirmed Purchase cancellation/refund state, and MySQL-backed commercial metrics. |
| `dde2263` | Durable reconciliation issues, operator acknowledge/resolve endpoints, scheduler locking, and reconciliation metrics. |

## Verified State

- `cd core-service && ./gradlew test` completed after Colima was started.
- 42 test result XML files were generated; no `<failure>` or `<error>` elements remained.
- `git diff --check` passed before each feature commit.
- Colima was initially off in this session. It is currently running, but do not assume that remains true in a later session.

## Deployment Requirement

Before deploying these commits to an existing MySQL database, apply both migrations once:

```text
scripts/migrations/2026-07-28-add-purchase-status-and-campaign-only-product.sql
scripts/migrations/2026-07-28-add-reconciliation-issues.sql
```

`ddl-auto: update` is not the reproducible cutover mechanism. In particular,
it will not remove or fully manage the intended existing-schema transition.

## Operational Semantics

- `Purchase(CONFIRMED)` is the durable commercial fact.
- `CANCELLED` and `REFUNDED` Purchase rows remain for audit. FCFS Entry and
  Redis admission slots are intentionally not reopened after a completed-payment
  cancellation.
- Dashboard commercial figures use confirmed MySQL Purchases; behavior funnel
  upper steps remain Elasticsearch-backed.
- Reconciliation issue workflow only records, exposes, acknowledges, and
  resolves observations. It does not automatically mutate Redis, Purchase,
  Product, or UserSummary.
- Reconciliation endpoints:
  - `GET /core/api/v1/reconciliation-issues`
  - `PATCH /core/api/v1/reconciliation-issues/{id}/acknowledge`
  - `PATCH /core/api/v1/reconciliation-issues/{id}/resolve`

## Dirty Worktree Boundary

The worktree is intentionally **not clean**. Do not revert or stage broadly.
Unrelated pending work includes, among others:

- APM/load-test scripts, nginx, and load-test documentation
- behavior-event schema/ES cutover work
- LLM/dashboard related changes
- `AGENTS.md`, `CLAUDE.md`, `README.md`, and `docs/plan/document-map.md`

Use `git status --short` and stage explicit paths only. The current dirty
`document-map.md` contains registrations for several unrelated documents; do
not commit it incidentally with a new feature.

## Next Work

Accepted next bounded investigation:

- Compare two Core consumption/persistence boundaries:
  1. bounded internal queue + Kafka pause/resume + offset/durable handoff after persistence
  2. remove internal command/purchase queues, finish durable DB processing before listener return, and use Kafka lag as the backlog
- Test the queue-removal path first because the latest FCFS 800/1,000 metrics showed no sustained Core backlog.
- Before implementation, record the effective Spring Kafka acknowledgment/commit mode and design a failure-injection test for the interval between delivery and DB commit.
- Do not implement a bounded in-memory queue alone: branch 1 is valid only when offset advancement follows durable processing or a durable inbox handoff.
- Re-run the same payment load scenarios and compare throughput, lag, DB-pool usage, DB convergence, loss, and duplicates.

Potential topics discussed but not accepted for immediate implementation:

- final repeatable external load-test measurement at stable network conditions
- Elasticsearch behavior-event operational hardening/cutover
- broader scheduler operational history or notification integration
- Core package refactoring and API error/DTO cleanup
