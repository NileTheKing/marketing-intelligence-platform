# FCFS Ingress Operating-Window A/B Plan

Status: active

## Goal

Produce one defensible cumulative performance comparison for the FCFS ingress
path. This is separate from the final `3,000 VU / FCFS 800` flash-crowd
capacity validation.

The comparison answers one question only:

> Under the same payment flow and a controlled event-open arrival rate near
> the former configuration's operating limit, how much does the combined
> ingress-path tuning improve the Entry reservation response time?

Do not use the result to assign a percentage contribution to any one change.
It is a cumulative comparison of the named baseline and current profile.

## Why A Separate Shape

`3,000 users / 3,000 VUs / FCFS 800` remains the product-relevant flash crowd
validation. Replaying that exact burst against the former profile makes the
baseline fail or clip at the request timeout. A timeout-clipped baseline is
useful for finding a limit, but not for a fair p95 comparison.

The A/B therefore keeps the same business transaction and population, but
releases attempts at a fixed rate. It represents an event-open window where
attempts arrive over several seconds. It does **not** claim that Axon currently
implements a waiting room or rate limiter.

## Fixed Business Conditions

| Item | Fixed value |
|---|---|
| Flow | `payment` |
| Eligible users | 3,000 unique users |
| FCFS capacity | 800 |
| Route | Mac external client -> public host nginx -> axon-nginx -> Entry |
| Metadata cache | `PRELOAD_CAMPAIGN_META=true` on both sides |
| Core CPU | `1.2` |
| Host nginx | `worker_connections=4096`, `worker_rlimit_nofile=16384` on both sides |
| Seed and tokens | reset and regenerated before every warm-up or measured run |
| Request timeout | `10s`; a run that reaches it is rejected as an A/B result |

The host nginx `768` connection-limit failure is not reproduced. It was a
confirmed configuration defect, not an acceptable historical performance
profile.

## Compared Profiles

| Profile | Entry CPU | axon-nginx CPU | Entry behavior-event publisher |
|---|---:|---:|---|
| A: former ingress profile | `0.6` | `0.1` | shared default async executor |
| B: current ingress profile | `1.5` | `0.5` | dedicated `backendEventTaskExecutor` |

Build A in an isolated temporary worktree. Starting from the same current
source revision as B, reverse only the dedicated executor wiring introduced by
`5c52e269b4227cede8129317d58b6f998a9336b1`, then use a Compose override for
the two CPU limits. Validate that A builds and its focused Entry tests pass
before any load run. Do not lower host nginx limits.

This is a controlled reconstruction of the former ingress profile, not a full
checkout of an old repository revision. Later correctness changes remain fixed
between A and B.

## Traffic Shape

Add a k6 `event_window` scenario using `constant-arrival-rate`.

| Parameter | Meaning |
|---|---|
| `EVENT_INGRESS_RPS` | new unique FCFS attempts started each second |
| `NUM_USERS=3000` | exactly 3,000 distinct users available to the scenario |
| Duration | `3000 / EVENT_INGRESS_RPS` seconds for rates that divide 3,000 |
| `MAX_VUS=3000` | generator capacity must not become the bottleneck |
| `ARRIVAL_PRE_ALLOCATED_VUS` | sized high enough that k6 reports no dropped iterations |

The first candidate rates are `60`, `75`, `100`, and `120` requests/second.
They produce 50, 40, 30, and 25 second event windows respectively. These are
pilot values, not portfolio results.

For each candidate, run profile A once after an unmeasured same-profile warm-up.
Choose the highest rate that satisfies every gate below:

- 800 FCFS approvals and zero unexpected FCFS errors
- no k6 dropped iterations
- no request timeout or nginx 5xx
- Entry successful reservation p95 below 5 seconds
- Redis counter and user set are both 800
- Entry and Purchase ledgers converge to 800

If `60` fails, search lower rates. If `120` passes comfortably, test higher
divisors such as `150` and `200`. Freeze the selected rate before B is measured.
It must not be changed after seeing B's result.

## Measurement Protocol

1. Record Git SHA, VM resource limits, host nginx values, k6 version, and the
   generator network session.
2. Deploy profile A, restart axon-nginx after Entry recreation, and run one
   unmeasured full `event_window` warm-up at the chosen rate.
3. Reset seed data and tokens. Run A three times with fresh data each time.
4. Deploy profile B. Run the same unmeasured warm-up, reset data, then run B
   three times under the identical rate and route.
5. Keep all six artifacts. Use the median of each profile's three measured
   runs. Warm-up output is never a headline number.

The wrapper must retain the existing domain checks and additionally record
`dropped_iterations`, arrival rate, derived duration, and the effective
resource profile in `run-meta.txt`.

## Headline Metric And Guardrails

### Headline

Entry server-side `POST /api/v1/entries`, `status=200`, p95. Derive it from the
delta of the Entry Prometheus `http_server_requests_seconds_bucket` snapshots
before and after each run.

This measures the winning user's reservation response inside Entry. It excludes
the 2,200 valid sold-out `410` responses, which would otherwise make an
all-request p95 look faster while hiding winner delay.

### Required guardrails

- FCFS approvals: `800`; overbooking: `0`
- Entry timeout and unexpected 5xx: `0`
- Redis users/counter: `800/800`
- MySQL Entry/Purchase ledgers: `800/800`
- Kafka consumer lag, Hikari pending, command DLT: `0` at convergence
- k6 external `reservation_duration` and `http_req_duration` p95 retained as
  supporting evidence, not mixed into the server-side headline

## Reporting Boundary

Safe final wording after the measured artifacts exist:

> Oracle VM event-open simulation with 3,000 eligible users and 800 FCFS slots:
> combined ingress-path tuning reduced Entry successful reservation p95 from
> `A` to `B` at `R` requests/second, while preserving 800/800 Redis and MySQL
> convergence with zero timeout or overbooking.

Replace `A`, `B`, and `R` only with the medians and frozen rate from the six
accepted runs. Keep the independent `3,000 VU / 800` flash-crowd result as a
capacity validation, not as the before side of this comparison.

## Implementation Checklist

- [ ] Add `event_window` to `scripts/load-test/k6-fcfs-load-test.js`.
- [ ] Pass `EVENT_INGRESS_RPS` and derived duration through the external
      wrapper and artifact metadata.
- [ ] Extract status-200 Entry server p95 from Prometheus histogram deltas.
- [ ] Update the nginx preflight to accept the current unauthenticated `401`.
- [ ] Create the isolated profile-A worktree and Compose override.
- [ ] Run rate-selection pilots, freeze the rate, then execute the six-run A/B.
- [ ] Record results in the portfolio evidence source only after all gates pass.

## Related Documents

- `docs/plan/oracle-compose-baseline-runbook.md`: route, artifact, and final
  flash-crowd capacity procedure
- `docs/devlog/dev-log-2026-07-10-fcfs-3000vu-bottleneck.md`: historical
  bottleneck observations and rejected configurations
