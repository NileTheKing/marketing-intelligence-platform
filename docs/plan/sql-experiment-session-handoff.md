# SQL experiment session handoff

Status: active, temporary handoff. Updated: 2026-10-15.

## Start Here

- Repository: `/Users/yangnail/dev/projects/skusw/axon`, branch `main` at handoff.
- Read `AGENTS.md`, `docs/architecture-map.md`, and `docs/plan/document-map.md` first.
- Current source: `docs/plan/abandoned-cart-coupon-candidate-query-v1-plan.md`.
- There are MANY modified and untracked files, including earlier campaign, load-test, and SQL work. Preserve all of them. Do not reset, delete, commit, or push without user instruction.
- This handoff was prepared without running new benchmarks or changing application logic.

## User Goal And Decisions

The user wants convincing SQL/index work grounded in a plausible business need, not a fast query made marginally faster. Earlier cohort aggregation and campaign reporting experiments felt weak because the business urgency was unclear. Do not reopen that search without a reason.

Accepted scenario: a cart-abandonment campaign considers customers after 30 minutes. A backlog of 50,000 eligible candidates exists among 5 million historical candidates across 20 campaigns. A scheduler selects 500 at a time and hands coupon commands to the existing Kafka pipeline. Actual issuance ends at MySQL UserCoupon storage. Faster candidate selection reduces the shared DB work before considering worker scale-out. The 10-minute backlog-drain target is a synthetic requirement, not a production SLA or measured conversion uplift.

- Five million rows are candidate HISTORY, not five million purchases or simultaneously pending jobs.
- Separate event-driven candidate cancellation from SQL tuning of the due-candidate lookup. Do not expand into unrelated features to justify an index.
- Production integration of actual Entry/SDK cart events is not finished. The Core candidate consumer exists; do not describe a complete live storefront journey.
- Worker scale-out, read replicas, AI, and payment redesign are not the current task.
- User values business reasoning but wants concise answers, not an evidence ledger. Use developer terms, avoid unexplained internal names and the middle-dot separator.

## Work Allocation

The user explicitly wants heavy experiments and routine implementation delegated to Luna to save the main agent's tokens and context. Main agent should define scope, reason, and review compact artifacts, not absorb full logs.

The previous Luna agent hit its usage limit and was closed. The main agent continued SQL Lab setup locally; the user objected. Going forward, if delegation is unavailable, tell the user rather than silently doing heavy work. Do not create a separate user-owned chat or worktree as a substitute for a subagent without authorization. Resolve currently available model names before dispatch; do not silently substitute Terra or another model.

## Implementation Map

- Candidate model: `core-service/src/main/java/com/axon/core_service/domain/marketing/AbandonedCartCouponCandidate.java`.
- Repository: `core-service/src/main/java/com/axon/core_service/repository/AbandonedCartCouponCandidateRepository.java`.
- Candidate service and scheduler: `AbandonedCartCouponCandidateService`, `AbandonedCartCouponScheduler`.
- Core cart-event handler: `AbandonedCartBehaviorConsumer`.
- Candidate states: SCHEDULED, PROCESSING, DISPATCHED, CANCELLED. Conditional updates claim individual candidates; existing Execution/Dispatch and coupon Kafka consumer are reused.
- Current application index is `(campaign_id, status, due_at, id)`. New ID-first query design is ONLY in the benchmark harness, not adopted into the production repository.
- Creation migration: `scripts/migrations/2026-10-14-add-abandoned-cart-coupon-candidates.sql`.
- Existing old-index DB replacement: `scripts/migrations/2026-10-15-replace-abandoned-cart-candidate-due-index.sql`. Not deployed by this session.
- Real pipeline harness: `core-service/src/test/java/com/axon/core_service/benchmark/AbandonedCartCouponIssuanceE2EBenchmarkTest.java`.
- Runner: `scripts/benchmark/run-abandoned-cart-issuance-e2e.sh`.

## Results: Keep The Measurement Boundaries Separate

### 1. Historical ID-only microbenchmark

About 799ms to 1ms, but only selecting IDs. NOT the real detailed JPA query or worker. Do not use it as actual issuance speed.

### 2. Same full joined query, index comparison

Artifacts: `build/benchmark/abandoned-cart-joined-index-order-5m/`.

- No dedicated due index: 13,221ms median.
- `(status, due_at, id)`: 13,391ms median; optimizer did not choose it.
- `(campaign_id, status, due_at, id)`: 419ms median.
- Optimizer starts with campaigns. Campaign-first index enables the candidate join lookup; global ordering still remains. Do not claim it scans only the returned 50,000 index entries.
- Separate empty-table 10k-write samples exist: updates 52/115/130ms, inserts 135/183/167ms respectively. Not established sustained production write overhead.

### 3. Real coupon issuance, 600-second A/B

Artifacts: `build/benchmark/abandoned-cart-issuance-e2e/summary.md`, `summary.json`, `arm-A/`, `arm-B/`.

- Same query, data size and preparation, separate disposable MySQL/Kafka/Redis fixtures, actual Spring scheduler and coupon consumer. One full run per arm.
- A: no dedicated candidate-query index, PK and business UNIQUE retained. At 600s: 16,000 UserCoupons, 34,000 targets unissued.
- B: campaign-first index. At 600s: 34,798 UserCoupons, 15,202 targets unissued.
- Same-time issuance increased 2.174875x, roughly 2.2x. Duplicates and DLT: zero in both.
- BOTH missed 50,000 in 10 minutes. Never claim B met the deadline.
- Separate pre-run repository timings: 14,892ms versus 596ms; not the same sample as the SQL median above.
- Post-cutoff drain counts are not 600s results. Recorded Kafka lag 406/0 was collected AFTER consumer shutdown, not live lag at the cutoff.
- Snapshot state totals are sequential, not an atomic reconciliation. `outstandingCandidates` includes future jobs; use target unissued counts above.

Resume wording already given to user:

> 장바구니 이탈 고객의 쿠폰 발급 적체 상황을 재현. EXPLAIN ANALYZE 기반 복합 인덱스 개선 → 후보 이력 500만 건에서 10분간 실제 쿠폰 발급량 약 2.2배 증가

This wording belongs to this index-only full-pipeline experiment, not the next query-design comparison.

### 4. Short repeated 500-coupon design comparison

Artifacts: `build/benchmark/abandoned-cart-single-page/summary.md`, `summary.json`, `identity.json`, sample JSON and explain files.

- A: existing full join plus campaign-first index.
- B: select oldest 500 IDs using status-first index, then fetch FULL candidate/campaign/action fields with real JPA and restore order. NOT IDs-only, and NOT index-only A/B.
- One warmup and 3 measured samples per design, A then B, same 500 restored between runs. Other 4,999,500 rows fingerprint unchanged.
- Detailed lookup median: A 459.61ms, B 14.89ms, about 96.8% lower.
- Actual 500 coupons: A [7461.96, 7534.65, 5940.27]ms; B [4956.04, 5200.31, 7554.47]ms. Medians 7461.96/5200.31ms, but ranges overlap. Treat overall improvement as preliminary.
- All 8 samples including warmups issued 500 actual coupons, with new successful dispatches, no duplicates or DLT, and zero lag after quiescence.
- Warm-buffer, same-page, order bias and 100ms completion polling apply. Multiplying medians by 100 is only an estimate, not proof of 50k deadline compliance.
- Runner modes: `single-page`, `single-page-smoke`. Do not run `full` unless a long run is actually requested.

## Immediate Task: Persistent Local MySQL Lab

User authorized a reusable experiment MySQL because rebuilding million-row fixtures on every run is wasteful.

New, uncommitted files:

- `compose.benchmark.yml`: standalone project `axon-benchmark`, MySQL 8.0.46, loopback `127.0.0.1:13306`, named volume `axon-benchmark_mysql-data`, 128MiB InnoDB buffer pool. Synthetic local credentials only; no real secrets.
- `scripts/benchmark/mysql-lab.py`: stdlib CLI, Docker-only access to this dedicated project, identity marker guard, fixed DB choices. No external application/production URL input.

Commands:

```sh
python3 scripts/benchmark/mysql-lab.py up
python3 scripts/benchmark/mysql-lab.py seed candidates
python3 scripts/benchmark/mysql-lab.py seed purchases
python3 scripts/benchmark/mysql-lab.py status
python3 scripts/benchmark/mysql-lab.py compare-candidates --repeat 3
python3 scripts/benchmark/mysql-lab.py sql purchases --file /path/to/query.sql
python3 scripts/benchmark/mysql-lab.py stop
```

- `bench_candidates`: 5M history, 50k due, 20 campaigns. Fixed logical time `2026-10-14 12:00:00`, not NOW(). 1% due, 4% future, 10% cancelled, 85% dispatched. Only candidate/campaign/action domain tables.
- `bench_purchases`: 1M synthetic Purchase rows, current fields/indexes. Users 1..800 have 8,000 purchases, first 800 CAMPAIGNACTIVITY for campaign 1, remaining SHOP. Not a complete application/cohort fixture.
- Seed runs INSERT SELECT inside MySQL and uses fixture version/count/ready metadata. Same valid seed should reuse data; `--reset` explicitly replaces only that lab DB.
- `compare-candidates` is SQL ONLY, full 500-row content/order hashes checked. One warmup, repeated SQL timing, plans and summary in `build/benchmark/mysql-lab/<timestamp>/`. Excludes JPA, Kafka and issuance. It switches the dedicated index and leaves B's status-first index afterward.
- Existing destructive JPA/Testcontainers benchmarks remain disposable; they do NOT automatically use this persistent DB or skip their own seeds.

### Completed And Actually Checked

- Dedicated container `axon-benchmark-mysql-1` created and healthy at setup.
- 100k candidate smoke seed and one SQL comparison passed, same 500 full rows. Artifact `build/benchmark/mysql-lab/20261015-162007` (23.684/5.722ms, smoke only).
- Replaced only own smoke fixture with 5M candidates; seed finished successfully.
- 1M purchase seed finished successfully, exit 0. Last process session 93269 was polled to completion; nothing is intentionally still running.
- CLI help and Python compilation checked during implementation. Small later edits warrant final recheck.

### Original SQL Lab Checklist (completed 2026-10-15; see results below)

1. Inspect actual current container/volume state. Re-run both seed commands and confirm they reuse, not rebuild, the 5M/1M data.
2. Recreate ONLY the dedicated MySQL container while retaining the volume, then confirm both datasets survive. Never use `down -v` or touch application/VM DBs.
3. Run persisted 5M SQL-only comparison with 3 repeats. Review summary, row hashes, and plans, not full logs. Do not equate it with JPA or issuance measurements.
4. Check loopback port binding, fixture counts, Python syntax, benchmark Compose validity, and `git diff --check`.
5. Add compact actual verification results to the existing active candidate plan. Report what is complete; leave container/data ready for reuse. No commit or push requested.

Use escalation if Docker access requires it. Previously approved command prefix was `python3 scripts/benchmark/mysql-lab.py`; permissions may differ in a new session.

## Broader Context, Not Immediate Work

The project handles FCFS traffic and behavior-driven marketing. Earlier campaign incrementality, dormant-customer, AI/HITL, and observability work exists in the tree and document map. Preserve it, but do not re-read all historical plans or restart those projects for this task. The current focus is finishing the SQL Lab and deciding from existing artifacts whether another bounded SQL comparison is necessary.

## Handoff Lifecycle

This is a temporary handoff, not a new competing source of truth. After reading the relevant code/artifacts and carrying necessary decisions into the existing active plan, remove this file and its document-map entry. Do not delete merely because it has been read, or delete other plans, code, datasets, or artifacts as part of handoff cleanup. If essential context is not yet preserved elsewhere, keep this file until it is.


## Resume Update: SQL Lab Complete, Query Candidate Review (2026-10-15)

- Read actual repository AGENTS.md before work, then architecture-map -> document-map -> project memory index. Current desktop worktree `/Users/yangnail/.codex/worktrees/f2d3/axon` is a clean detached checkout; the uncommitted SQL/application work remains in `/Users/yangnail/dev/projects/skusw/axon`. Do not assume f2d3 contains it or copy/reset broad changes.
- Luna completed original SQL Lab checklist 1–5. Both 5M candidate / 1M purchase seeds reused existing data, container-only recreation preserved the volume/data, loopback/Compose/Python/diff checks passed. Lab is left available. Artifact: `build/benchmark/mysql-lab/20261015-163820/`; join SQL median 346.903ms, IDs-first plus full-detail SQL median 6.044ms, same full ordered 500-row SHA-256. ID-selection EXPLAIN was saved separately because the CLI's normal B plan only explains the detail join. This is SQL-only, excludes JPA/Kafka/issuance. Results were carried into the active candidate plan.
- Subsequent discussion: do not defend keeping completed/cancelled candidate history in the hot work table to preserve a resume metric. Splitting/pruning history may be the better design; its speed and move/delete cost are unmeasured. Same-table query experiments remain conditional evidence, not proof of architecture choice. No split/refactor has been implemented.
- Current user goal is finding an honest, business-relevant SQL/index story for hiring. Need work that becomes materially worse when delayed, not an arbitrary fast report made faster. No resume/T-file writing requested in this repository. Keep replies concise and distinguish possibility from measured impact.
- ES was discussed as a possible candidate-search engine, not universally ruled out; fixed status/due-time lookup does not currently justify adding it. Queue-based delayed rechecks are also possible; inability to mutate queued events is not a blanket exclusion. No infrastructure change chosen.
- Reviewed existing cohort and incrementality experiments. Incrementality uses 100k total targets (90k/10k), not 100k treatment plus 10k holdout. Target purchase histories remain two rows per user in both 1M/5M fixtures. Indexed median 520/524ms thus does not test accumulated target-customer history. Actual plan filters dates after user lookup. Baseline removes the entire history index, not comparison with a user-only index. Five read samples and single writer overlap are limited. No urgent business reason for lowering a 7/30-day report from 1.5s to 0.5s was established.
- User approved a bounded code-based shortlist, no new benchmark runs. Completed shortlist and decision record are in `docs/plan/db-jpa-integrity-audit-2026-08-11.md`, section `SQL 성능 사례 후보 검토 (2026-10-15, 측정 전)`. This is the current place to resume candidate reasoning, not a new overlapping plan.
- Priority 1: synchronous CampaignActivityRun.prepare target count/selection (RFM segment + coupon NOT EXISTS). API and activity-lock dependency verified; no RFM-first index declared in examined entity/migrations. Slow SQL not measured. Must separate query time from snapshot INSERT/JPA work, inspect real indexes, and stop if naturally fast.
- Priority 2: review the EXISTING dormant-customer index case (183ms -> 7ms), not a new feature. Customer summary + separate delivery history avoids the abandoned-candidate history premise. It is a basic query optimization case with no proven deadline/E2E gain. Existing harness only checks count 500; ordered-ID equivalence not established. PK extension means explicit second index column may be redundant; do not overclaim it.
- Priority 3: existing daily prior-day Purchase/Entry anti-join reconciliation. Query filters type/status/date while current Purchase indexes lead with activity or user. No measured bottleneck or business deadline; lower priority.
- Excluded invented checkout-coupon bottleneck: getApplicableCoupons has no observed caller; actual coupon display is /mypage. Existing user_id index and ordinary per-user volume make forced thousands-per-user fixtures inappropriate. Other rejected/parked queries and criteria are in the audit.
- Next step is discussing the shortest Priority-1 check, not automatically running heavy experiments. Delegate heavy work to Luna only; report limits rather than silently substituting another model or executing it as parent. No commit/push, no reset, no production/VM DB changes. Handoff retained until all essential decisions are safely preserved and its lifecycle can be completed.


## Resume Update: 1M Candidate / Purchase Overlap Complete (2026-10-15)

- User explicitly requested Luna to test shared-DB interference: even if coupons finish in time, does candidate processing consume resources and slow real purchase persistence? Table split was not requested. No invented delay SLA, automatic stress escalation, or 600s full run.
- Luna implemented an opt-in `purchase-overlap` / `purchase-overlap-smoke` mode in the existing issuance benchmark and runner, plus property forwarding and conditional benchmark-only test compilation in `core-service/build.gradle`. Application query/index adoption was not changed.
- Main artifact: `build/benchmark/abandoned-cart-purchase-overlap-20261015T182311/`. Disposable MySQL/Kafka/Redis + actual scheduler/coupon consumer; 1M candidate rows seeded once, due 50k across 20 campaigns, 500/page. Future/due/cancelled/dispatched distribution: 4/5/10/81 percent. Existing persistent 5M/1M lab databases were not touched.
- Six windows baseline-A/A/B/baseline-B/B/A, each 10s warm-up then 60s fixed offers of 10 twenty-message ledger transactions/s. Actual `FcfsLedgerPersistenceService.persistBatch` commits Entry+Purchase; excludes Entry confirm HTTP, Kafka ingress and UserSummary projection. Every measured window offered/committed 600 tx, 12,000 Entry + 12,000 Purchase rows; fail/drop/missing/duplicate/DLT/final Kafka lag zero. All due state restored between windows. Core source-file fingerprints before/after match (`files` maps; capturedAt naturally differs).
- Results: purchase scheduled-offer-to-commit p95 baseline 70.16/66.27ms, A 51.51/50.19ms, B 72.28/70.03ms. Transaction-only p95 baseline 61.86/62.10ms, A 39.87/41.42ms, B 45.40/50.22ms. Thus neither purchase-delay harm from A nor purchase-delay relief from B was demonstrated under this load. Do not claim CPU reduction improved purchase latency. Causes of A's lower p95 were not isolated.
- Candidate detailed JPA lookup median A 231.50/229.94ms, B 23.46/18.07ms. Average MySQL cores baseline .192/.203, A .663/.697, B .629/.608. B had lower CPU than A in both windows, but this is query+index plus whole-worker comparison, not index-only or CPU attribution to one SQL. No consistent I/O improvement.
- Issued counts after drain A1/B1/B2/A2 = 8,500/9,500/10,500/9,000. These are NOT exact 60s cutoff counts. CPU/IO snapshots were taken around cutoff separately from drain; do not divide cutoff CPU by post-drain coupon counts as exact efficiency. Worker finished current page after cutoff (62.2–64.4s active), due 50k never exhausted. This does not establish full-drain time or ten-minute target compliance.
- Found Testcontainers MySQL's bundled my.cnf uses buffer pool16MiB/cache4/sort64KiB. Only this opt-in mode normalizes those to128MiB/4000/256KiB, verifies session/global values, records before/after; redo capacity100MiB; no added CPU/memory quota. Old measurements retain their original environment boundaries. SQL/application DEBUG logging is suppressed only in this mode. XML system-out/err capture disabled only for the mode after report-writer error; test result counts retained. Failed preliminary smoke artifacts remain separately preserved.
- Latest correct smoke and main passed JUnit1/skipped0/failures0/errors0. Full test-source compilation first exposed an unrelated FcfsCommandOrchestratorTest constructor mismatch; that test was not edited. Mode conditionally compiles only AbstractIntegrationTest and the benchmark. Do not report full-suite pass. Runner validates mode, six windows, raw latency/offer accounting and actual persisted rows. bash -n/git diff --check passed.
- Parent reviewed raw six window records and corrected a documentation transcription: B-2 lookup p95 is133.90ms, not217.51ms. Canonical results/limits are appended under Purchase Persistence Overlap Retest in existing active abandoned-cart plan; no new planning document created.
- Experiment is complete; no further experiment or service refactor automatically authorized as next work. User should receive the negative purchase-latency conclusion together with the observed candidate-lookup and CPU differences. No commit/push, no production/VM changes.

## Resume Update: Admin Purchase List SQL-only Design Check (2026-10-16)

- User explicitly authorized a bounded SQL-only comparison for the potential administrator purchase list. It is not an existing API/UI or an operational incident claim. No entity, migration, or service code changed.
- Reused only the verified persistent `bench_purchases` fixture (1,000,000 rows; ready). The 5M candidate fixture was read-only status-checked before and after (5M rows, ready), and not modified. A fixed 30-day half-open window was derived from the fixture's maximum timestamp: `[2026-09-08 00:00:00, 2026-10-08 00:00:00)`. Matches: 67,850 CONFIRMED and 3,078 CANCELLED.
- Same full-field query, `status`, date range, descending `purchase_at,id`, `LIMIT 50`; A original indexes, B `(status)`, C `(status,purchase_at,id)`, D `(status,purchase_at)`. One warm-up plus five timed samples per status/design; same 50-row ordered hashes per status. Both EXPLAIN forms saved. Index creation excluded from query timings.
- Complete artifact: `build/benchmark/admin-purchase-list-20261016T021433Z/`. Medians CONFIRMED A/B/C/D = 371.059/2684.622/0.817/1.169 ms; CANCELLED = 272.477/95.849/0.764/0.572 ms. A full-scans and filesorts; B status-only lookup filters many rows then filesorts; C/D use reverse range scan with no filesort in EXPLAIN Extra. D needs no explicit id in this tested plan/query; C gave no consistent advantage. Warm-cache/order bias and synthetic-data boundary apply; do not infer disk spill or general performance.
- First attempt `admin-purchase-list-20261016T021319Z/` measured all conditions but failed while serializing its summary and did not persist raw samples. It was not mixed into results; its `failure.json` identifies the recording bug. After read-only fixture/index restoration verification, a single successful rerun produced the complete artifact. Do not report the failed artifact as a valid result.
- Final check: fixture metadata and 1,000,000 row count unchanged; all original Purchase indexes match pre-run signature; no `bench_admin_*` index remains. SQL Lab stays available. No new experiment is automatically authorized. No commit/push.

## Resume Update: Admin Purchase List 1M-row Write Cost (2026-10-16)

- User explicitly authorized a private-copy comparison of existing Purchase indexes (A) against the same indexes plus `(status,purchase_at)` (B), with the persistent 1M Purchase and 5M candidate fixtures untouched. Each private table copied all 1M rows; copy/build times were excluded from SQL transaction timings. B's index was built on its still-empty private table in 161.8ms (not a 1M-row index-build measurement); A/B copy took 21.9s/50.7s.
- Each measured round appended 100 identical 20-row `INSERT SELECT` transactions per arm with COMMIT, using append-time synthetic `CONFIRMED/SHOP` rows. AB/BA order was used; each arm accumulated the same 2,000 new rows per round. Four rounds ran. Transaction p50/p95 ms: R1 A 4.251/9.894, B 3.032/8.096; R2 A 1.650/2.344, B 1.826/2.542; R3 A 1.982/3.063, B 2.005/2.901; R4 A 1.643/2.293, B 2.047/4.439. Full raw per-transaction samples and server arm elapsed times are in `build/benchmark/admin-purchase-write-1m-20261016T025733Z/`.
- The first round was much slower for both arms and ranking reversed. Cache/order influence is possible but not established. A bug in the live adaptive rule (20% cross-arm gap rather than between-round spread) caused rounds 3 and 4 to run; this is disclosed, and no more measurements were added. The result is inconclusive about reliable index write cost. The prior empty-table smoke at `admin-purchase-write-20261016T023131Z/` is historical only and not 1M-row write-cost evidence.
- Restore checks passed: original purchases remain 1,000,000 with unchanged row and index signatures; candidates remain 5,000,000/ready; only the two private tables were dropped. This is SQL `INSERT SELECT`, not Spring/JPA or HTTP behavior, and covers only the measured append pattern. No app schema/code change or commit/push.

## Resume Update: Admin Purchase List Write Cost Smoke (2026-10-16)

- User authorized a three-minute SQL-only write-cost smoke comparing original Purchase indexes vs an extra `(status,purchase_at)` index. No application DB/code/schema was changed.
- Artifact: `build/benchmark/admin-purchase-write-20261016T023131Z/`. Empty private tables used `CREATE TABLE LIKE purchases`; no 1M row copy. A/B each received identical synthetic 20-row `INSERT SELECT` transactions with COMMIT, warm-up then truncate, measured in one MySQL session excluding Docker/client startup. B index build 214.494ms recorded separately.
- Two initial AB/BA pairs produced a rank reversal; two additional pairs were run and ranking still reversed. A raw ms `[4.195,3.038,4.080,3.912]` (median 3.996); B `[3.112,5.203,2.949,5.050]` (median 4.081). Four tx/arm are insufficient for p95 or a reliable conclusion; treat index write cost as inconclusive. This SQL insert path is not Spring/JPA `IDENTITY` write cost and tables were empty rather than 1M rows.
- All 20-row counts and synthetic row hashes matched. Finally removed only the two created write tables. Read-only final status: persistent purchases 1M/ready and candidates 5M/ready; Purchase original index signature unchanged. No additional experiment authorized.


## Resume Update: Existing Serving Query Usage Review (2026-10-16)

- User requested a real caller/business-dependency review before selecting another SQL/index story. No benchmark, fixture/schema/service change, or new API was run/created.
- Current code contradicts the earlier claim that getApplicableCoupons had no caller: `/checkout` -> StoreViewService.getCheckoutViewData -> CouponService.getApplicableCoupons exists. Audit exclusion corrected. Existing user index/EntityGraph and ordinary per-user volume still give no measured bottleneck.
- Entry synchronously calls Core validation for HEAVY participation conditions, before Redis reservation on the new-entry path; RecentPurchaseLimit already uses UserSummary PK lookup. Do not invent Purchase scans or a missing composite index there.
- Existing activity dashboard template loads dashboard.js, which polls every 5s; campaign dashboard uses SSE every 5s. These have an actual recurring caller for campaign sales/conversion monitoring. Existing activity/status/period Purchase index and prior aggregate-query cleanup must be preserved as already-implemented work. SQL, ES, Redis and whole API costs are distinct; no new delay/CPU measurement exists.
- Preferred next investigation is the existing dashboard API and prior global repeated-query/SSE duplicate-aggregate changes, rather than attaching a hypothetical business caller to the admin purchase-list SQL. Existing campaign prepare is a synchronous workflow gate, not a high-frequency customer-serving API. Canonical details appended to existing DB/JPA audit. No new experiment automatically authorized.


## Resume Update: Abandoned-cart cancel UPDATE index check (2026-10-16)

- Completed a bounded SQL-only comparison for the real cancel UPDATE predicate on a private synthetic 100k-row fixture (25k users × 4 products, 20 campaigns; 99,500 SCHEDULED/500 PROCESSING). A retained the current entity/migration indexes; B added `(user_id,product_id)`. 25 matched update+commit and 25 absent-user no-match samples per arm/round, in AB then BA order.
- A p50/p95 matched = 32.499/36.459ms and 32.040/32.927ms; B = 3.149/4.890ms and 3.412/4.814ms. No-match timings are much shorter and reported separately: A 27.924/32.721 and 27.752/28.833ms; B 0.066/0.120 and 0.062/0.103ms. A EXPLAIN estimates 100k rows with PRIMARY full index scan; B estimates 1 row with the composite range index. Estimated, not actual examined rows.
- A single brief B→C same-target check compared composite with user-only. Matched p50/p95 B 2.795/4.238ms vs C 3.530/7.388ms; absent-user no-match 0.072/0.124 vs 0.111/0.188ms. EXPLAIN estimates 1 vs 4 rows. One unbalanced run is insufficient to claim composite necessity; no automatic additional load was run.
- Primary artifact `build/benchmark/abandoned-cart-cancel-100k-20261016T052824Z/`; C sub-artifact `user-only-vs-composite/`. Raw samples, UPDATE plans, scripts and cleanup/source checks are saved. Earlier pre-measurement attempts remain as failed artifacts with explicit reasons. Source candidates stayed at 5M/ready and their index signature was unchanged; all owned tables were dropped. SQL-only: no consumer/Kafka/HTTP/purchase latency, concurrency or row-lock claim. No application schema/code changed.
