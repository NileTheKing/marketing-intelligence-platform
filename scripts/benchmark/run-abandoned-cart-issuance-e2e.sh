#!/usr/bin/env bash
set -euo pipefail

mode="${1:-full}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
artifact_dir="$root/build/benchmark/abandoned-cart-issuance-e2e"

new_artifact_dir() {
  local base="$1"
  mkdir -p "$(dirname "$base")"
  mktemp -d "${base}-$(date +%Y%m%dT%H%M%S)-XXXXXX"
}

if [[ "$mode" != "smoke" && "$mode" != "full" && "$mode" != "single-page" && "$mode" != "single-page-smoke" && "$mode" != "purchase-overlap" && "$mode" != "purchase-overlap-smoke" ]]; then
  printf 'usage: %s [smoke|full|single-page|single-page-smoke|purchase-overlap|purchase-overlap-smoke]\n' "$0" >&2
  exit 2
fi

# Testcontainers does not automatically use the active OrbStack Docker context.
if [[ -z "${DOCKER_HOST:-}" ]] && docker context inspect orbstack >/dev/null 2>&1; then
  export DOCKER_HOST
  DOCKER_HOST="$(docker context inspect orbstack --format '{{.Endpoints.docker.Host}}')"
fi

if [[ "$mode" == purchase-overlap* ]]; then
  artifact_dir="$root/build/benchmark/abandoned-cart-purchase-overlap"
  [[ "$mode" != purchase-overlap-smoke ]] || artifact_dir="$artifact_dir/smoke"
  artifact_dir="$(new_artifact_dir "$artifact_dir")"
  (
    cd "$root/core-service"
    ./gradlew --no-daemon test --stacktrace --tests '*AbandonedCartCouponIssuanceE2EBenchmarkTest' \
      -Dabandoned.cart.issuance.e2e=true \
      -Dabandoned.cart.issuance.purchase-overlap=true \
      -Dabandoned.cart.issuance.smoke="$([[ "$mode" == purchase-overlap-smoke ]] && echo true || echo false)" \
      -Dabandoned.cart.issuance.artifact-dir="$artifact_dir"
  ) > "$artifact_dir/run.log" 2>&1 &
  child_pid=$!
  printf '{"stage":"setup","mode":"%s","childPid":%s,"nextStep":"compile and start one disposable shared fixture","blocker":null}\n' \
    "$mode" "$child_pid" > "$artifact_dir/progress.json"
  if ! wait "$child_pid"; then
    tail -n 60 "$artifact_dir/run.log" > "$artifact_dir/failure-tail.txt"
    printf '{"stage":"failed","mode":"%s","childPid":%s,"nextStep":"inspect failure-tail.txt","blocker":"purchase-overlap test failed"}\n' \
      "$mode" "$child_pid" > "$artifact_dir/progress.json"
    exit 1
  fi
  python3 - "$artifact_dir/summary.json" "$mode" <<'PY'
import json
import sys
from pathlib import Path
summary = json.loads(Path(sys.argv[1]).read_text())
mode = sys.argv[2]
smoke = mode == "purchase-overlap-smoke"
assert summary["mode"] == mode, summary.get("mode")
assert summary["historyRows"] == (100_000 if smoke else 1_000_000)
assert summary["dueRows"] == (500 if smoke else 50_000)
windows = summary["windows"]
assert len(windows) == (3 if smoke else 6)
for window in windows:
    assert window["purchase"]["committed"] == len(window["purchase"]["rawSamples"])
    assert window["purchase"]["failed"] == 0
    assert window["purchase"]["offered"] == window["purchase"]["enqueued"] + window["purchase"]["dropped"]
    assert window["purchase"]["enqueued"] == window["purchase"]["committed"] + window["purchase"]["failed"]
    if smoke:
        assert window["purchase"]["dropped"] == window["purchase"]["missingOffers"] == 0
    assert len(window["purchase"]["rawSamples"]) > 0
    assert window["persistedPurchaseRows"] == window["persistedEntryRows"] == 20 * window["purchase"]["committed"]
    assert window["kafkaLagAfterQuiescence"] == 0 and window["dltRecords"] == 0
assert summary["mysqlEffectiveSettings"]["innodb_buffer_pool_size"] == 134217728
assert summary["mysqlEffectiveSettings"]["table_open_cache"] == 4000
assert summary["mysqlEffectiveSettings"]["sort_buffer_size"] == 262144
assert summary["mysqlEffectiveSettings"]["session_sort_buffer_size"] == 262144
assert summary["mysqlEffectiveSettings"]["innodb_redo_log_capacity"] == 104857600
PY
  exit
fi

if [[ "$mode" == single-page* ]]; then
  artifact_dir="$root/build/benchmark/abandoned-cart-single-page"
  [[ "$mode" != single-page-smoke ]] || artifact_dir="$artifact_dir/smoke"
  artifact_dir="$(new_artifact_dir "$artifact_dir")"
  (
    cd "$root/core-service"
    ./gradlew --no-daemon --rerun-tasks test --tests '*AbandonedCartCouponIssuanceE2EBenchmarkTest' \
      -Dabandoned.cart.issuance.e2e=true \
      -Dabandoned.cart.issuance.single-page=true \
      -Dabandoned.cart.issuance.smoke="$([[ "$mode" == single-page-smoke ]] && echo true || echo false)" \
      -Dabandoned.cart.issuance.artifact-dir="$artifact_dir"
  ) > "$artifact_dir/run.log" 2>&1 &
  child_pid=$!
  printf '{"stage":"setup","mode":"%s","childPid":%s,"nextStep":"compile and start single shared fixture","blocker":null}\n' \
    "$mode" "$child_pid" > "$artifact_dir/progress.json"
  if ! wait "$child_pid"; then
    tail -n 60 "$artifact_dir/run.log" > "$artifact_dir/failure-tail.txt"
    printf '{"stage":"failed","mode":"%s","childPid":%s,"nextStep":"inspect failure-tail.txt","blocker":"single-page test failed"}\n' \
      "$mode" "$child_pid" > "$artifact_dir/progress.json"
    exit 1
  fi
  python3 - "$root/core-service/build/test-results/test/TEST-com.axon.core_service.benchmark.AbandonedCartCouponIssuanceE2EBenchmarkTest.xml" \
    "$artifact_dir/summary.json" "$mode" <<'PY'
import json
import sys
import xml.etree.ElementTree as ET
suite = ET.parse(sys.argv[1]).getroot()
assert all(int(suite.attrib.get(key, 0)) == expected for key, expected in
           (("tests", 1), ("skipped", 0), ("failures", 0), ("errors", 0))), suite.attrib
summary = json.load(open(sys.argv[2]))
expected = 1 if sys.argv[3] == "single-page-smoke" else 3
for design in ("A", "B"):
    samples = [s for s in summary["samples"] if s["design"] == design and not s["warmup"]]
    assert len(samples) == expected
    assert all(s["committedUserCoupons"] == 500 and s["completedCandidates"] == 500
               and s["succeededDispatches"] == 500 and s["executions"] == 500
               and s["kafkaLagAfterQuiescence"] == 0 and s["dltRecords"] == 0 for s in samples)
PY
  exit 0
fi

artifact_dir="$(new_artifact_dir "$artifact_dir")"

run_arm() {
  local arm="$1"
  local log="$artifact_dir/${mode}-arm-${arm}.log"
  local pid
  printf '{"stage":"%s","arm":"%s","childPid":%s,"issued":0,"targetDue":%s,"boundary":"scheduler to committed target UserCoupon rows","nextStep":"running %s arm","blocker":null}\n' \
    "$mode" "$arm" "$$" "$([[ "$mode" == smoke ]] && echo 20 || echo 50000)" "$arm" \
    > "$artifact_dir/progress.json"
  (
    cd "$root/core-service"
    ./gradlew --no-daemon --rerun-tasks test --tests '*AbandonedCartCouponIssuanceE2EBenchmarkTest' \
      -Dabandoned.cart.issuance.e2e=true \
      -Dabandoned.cart.issuance.arm="$arm" \
      -Dabandoned.cart.issuance.smoke="$([[ "$mode" == smoke ]] && echo true || echo false)" \
      -Dabandoned.cart.issuance.artifact-dir="$artifact_dir"
  ) > "$log" 2>&1 &
  pid=$!
  printf '{"stage":"%s","arm":"%s","childPid":%s,"issued":0,"targetDue":%s,"boundary":"scheduler to committed target UserCoupon rows","nextStep":"Gradle test worker active","blocker":null}\n' \
    "$mode" "$arm" "$pid" "$([[ "$mode" == smoke ]] && echo 20 || echo 50000)" \
    > "$artifact_dir/progress.json"
  if ! wait "$pid"; then
    printf '{"stage":"failed","arm":"%s","childPid":%s,"issued":0,"nextStep":"inspect targeted failure tail in %s","blocker":"test process failed; logs kept in artifact directory"}\n' \
      "$arm" "$pid" "$(basename "$log")" > "$artifact_dir/progress.json"
    tail -n 60 "$log" > "$artifact_dir/${mode}-arm-${arm}-failure-tail.txt"
    return 1
  fi
  python3 - "$root/core-service/build/test-results/test/TEST-com.axon.core_service.benchmark.AbandonedCartCouponIssuanceE2EBenchmarkTest.xml" \
    "$artifact_dir" "$mode" "$arm" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET

report = pathlib.Path(sys.argv[1])
root = pathlib.Path(sys.argv[2])
mode, arm = sys.argv[3:]
result_dir = root / (f"smoke-arm-{arm}" if mode == "smoke" else f"arm-{arm}")
if not report.exists():
    raise SystemExit("JUnit report missing; benchmark did not execute")
suite = ET.parse(report).getroot()
if int(suite.attrib.get("tests", "0")) != 1 or int(suite.attrib.get("skipped", "0")) != 0:
    raise SystemExit("JUnit benchmark was skipped or did not run exactly once")
if not (result_dir / "cutoff.json").exists():
    raise SystemExit("cutoff.json missing; refusing to treat this arm as a benchmark result")
PY
}

run_arm A
run_arm B
python3 - "$artifact_dir" "$mode" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
mode = sys.argv[2]
expected_due = 20 if mode == "smoke" else 50000
if mode == "smoke":
    a = json.loads((root / "smoke-arm-A" / "cutoff.json").read_text())
    b = json.loads((root / "smoke-arm-B" / "cutoff.json").read_text())
else:
    a = json.loads((root / "arm-A" / "cutoff.json").read_text())
    b = json.loads((root / "arm-B" / "cutoff.json").read_text())
for name, arm in (("A", a), ("B", b)):
    if arm["targetDue"] != expected_due:
        raise SystemExit(f"{name}: expected targetDue={expected_due}, got {arm['targetDue']}")
    if arm["committedUserCoupons"] > expected_due or arm["duplicateUserCouponPairs"] != 0:
        raise SystemExit(f"{name}: issuance count or duplicate assertion failed")
    arm["targetUnissuedByCutoff"] = expected_due - arm["committedUserCoupons"]
post_cutoff = {}
if mode == "smoke":
    post_cutoff = {
        name: json.loads((root / f"smoke-arm-{name}" / "post-cutoff-drain.json").read_text())
        for name in ("A", "B")
    }
else:
    post_cutoff = {
        name: json.loads((root / f"arm-{name}" / "post-cutoff-drain.json").read_text())
        for name in ("A", "B")
    }
for record in post_cutoff.values():
    record["kafkaDiagnosticsTiming"] = "after test command consumer stopped"
summary = {
    "mode": mode,
    "scenario": ("2K candidates, 20 due smoke targets" if mode == "smoke" else
                 "5M candidates, 20 campaigns, 50K due targets")
                + "; production scheduler/Kafka/UserCoupon path",
    "cutoffSeconds": 45 if mode == "smoke" else 600,
    "arms": {"A": a, "B": b},
    "comparison": {
        "additionalCommittedTargetCouponsBvsA": b["committedUserCoupons"] - a["committedUserCoupons"],
        "issuanceUpliftBvsA": b["committedUserCoupons"] / a["committedUserCoupons"] if a["committedUserCoupons"] else None,
        "bothMetCutoffTarget": a["committedUserCoupons"] == expected_due and b["committedUserCoupons"] == expected_due,
    },
    "postCutoffDrain": post_cutoff,
    "snapshotCaveat": "Candidate and dispatch state aggregates are sequential, non-atomic all-history scans; outstandingCandidates includes future scheduled rows and is not target-unissued backlog.",
}
(root / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
lines = ["# Abandoned-cart coupon issuance benchmark", "",
         f"Mode: {mode}; target cutoff: {summary['cutoffSeconds']} seconds (snapshots occur early on full issuance).", "",
         "| Arm | Committed target UserCoupon rows at cutoff | Target unissued at cutoff | Duplicates | Completion observed |",
         "| --- | ---: | ---: | ---: | ---: |"]
for name, arm in summary["arms"].items():
    completion = (f"{arm['issuanceCompletionElapsedSeconds']}s"
                  if arm['issuanceCompletionElapsedSeconds'] is not None else "Not completed")
    lines.append(f"| {name} | {arm['committedUserCoupons']} | {arm['targetUnissuedByCutoff']} | "
                 f"{arm['duplicateUserCouponPairs']} | {completion} |")
lines += ["", f"B/A issuance uplift: {summary['comparison']['issuanceUpliftBvsA']:.6f}x "
          f"(+{summary['comparison']['additionalCommittedTargetCouponsBvsA']} committed target coupons).",
          "Cutoff counts are committed UserCoupon rows for target users. Post-cutoff drain is separate and does not change cutoff results.",
          "Candidate/dispatch state totals are sequential, non-atomic all-history scans; outstanding candidates include future scheduled rows and must not be read as target-unissued backlog.",
          "Post-stop Kafka lag/DLT diagnostics are collected after the command consumer stops; they do not measure live lag at the cutoff.",
          "Cart-event ingestion is excluded. Per-arm plans, logs, schema details, Kafka diagnostics, and drain snapshots are in sibling artifacts."]
(root / "summary.md").write_text("\n".join(lines) + "\n")
(root / "progress.json").write_text(json.dumps({
    "stage": "done",
    "mode": mode,
    "arm": "A+B",
    "childPid": None,
    "latestCommittedByArm": {name: arm["committedUserCoupons"] for name, arm in summary["arms"].items()},
    "targetDue": expected_due,
    "boundary": "scheduler to committed target UserCoupon rows",
    "nextStep": "review summary.md and summary.json",
    "blocker": None,
}, indent=2) + "\n")
PY
