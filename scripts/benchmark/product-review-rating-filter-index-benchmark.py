#!/usr/bin/env python3
"""Bounded SQL-only comparison for low-rating latest reviews on the retained fixture."""

import hashlib
import importlib.util
import json
from datetime import datetime, timezone
from pathlib import Path
import shutil
import statistics
import sys

ROOT = Path(__file__).resolve().parents[2]
BASE_PATH = ROOT / "scripts/benchmark/product-review-index-benchmark.py"
spec = importlib.util.spec_from_file_location("review_base", BASE_PATH)
base = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = base
spec.loader.exec_module(base)

SCHEMA = base.SCHEMA
C_INDEX = "idx_review_product_rating_created_tmp"
TARGETS = base.TARGETS
original_source = None
original_fixture = None
original_b_indexes = None
created_by_this_run = False
index_events = []


def index_exists():
    return base.lab.sql(
        "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
        f"WHERE table_schema='{SCHEMA}' AND table_name='reviews_b' AND index_name='{C_INDEX}'"
    ) == "1"


def filter_query(table, product_id, limit=21):
    suffix = f" LIMIT {limit}" if limit is not None else ""
    return (f"SELECT id,rating,content,created_at FROM {table} "
            f"WHERE product_id={product_id} AND rating=1 "
            f"ORDER BY created_at DESC,id DESC{suffix}")


def filtered_count(table, product_id):
    return int(base.lab.sql(
        f"SELECT COUNT(*) FROM {table} WHERE product_id={product_id} AND rating=1", SCHEMA))


def sample(table, target, product_id, expected_count, phase, round_index=None, design=None):
    output = base.lab.sql(
        "SET @review_started=SYSDATE(6);" + filter_query(table, product_id)
        + ";SELECT 'REVIEW_ELAPSED_MS',"
          "TIMESTAMPDIFF(MICROSECOND,@review_started,SYSDATE(6))/1000", SCHEMA)
    lines = output.splitlines()
    elapsed = float(lines[-1].split("\t")[1])
    rows = lines[:-1]
    expected_page = min(21, expected_count)
    if len(rows) != expected_page:
        raise RuntimeError(f"{target}/{design}: expected {expected_page} page rows, got {len(rows)}")
    result = {"phase": phase, "target": target, "design": design,
              "server_elapsed_ms": elapsed,
              "first_page_rows": len(rows),
              "first_page_sha256": hashlib.sha256("\n".join(rows).encode()).hexdigest()}
    if round_index is not None:
        result["round"] = round_index
    return result


def full_result(table, product_id):
    rows = base.lab.sql(filter_query(table, product_id, limit=None), SCHEMA).splitlines()
    return {"rows": len(rows), "sha256": hashlib.sha256("\n".join(rows).encode()).hexdigest()}


def set_c_index(present):
    global created_by_this_run
    if present:
        if index_exists():
            raise RuntimeError(f"temporary index collision: {C_INDEX} already exists")
        elapsed = base.timed_sql(
            f"CREATE INDEX {C_INDEX} ON reviews_b(product_id,rating,created_at)")
        created_by_this_run = True
        index_events.append({"action": "create", "server_elapsed_ms": elapsed})
    else:
        if not index_exists():
            raise RuntimeError(f"temporary index disappeared before owned drop: {C_INDEX}")
        elapsed = base.timed_sql(f"DROP INDEX {C_INDEX} ON reviews_b")
        created_by_this_run = False
        index_events.append({"action": "drop", "server_elapsed_ms": elapsed})


def record_plans(artifact, design, table, with_c_index=False):
    if with_c_index:
        set_c_index(True)
    try:
        for key, (product_id, _) in TARGETS.items():
            sql = filter_query(table, product_id)
            (artifact / f"explain-{key}-{design}.txt").write_text(
                base.lab.sql("EXPLAIN " + sql, SCHEMA) + "\n")
            (artifact / f"explain-analyze-{key}-{design}.txt").write_text(
                base.lab.sql("EXPLAIN ANALYZE " + sql, SCHEMA) + "\n")
    finally:
        if with_c_index and index_exists():
            set_c_index(False)


def verify_restored():
    fixture = base.verify_fixture()
    if fixture["metadata"] != original_fixture["metadata"]:
        raise RuntimeError("review fixture metadata changed")
    if fixture["indexes_a"] != original_fixture["indexes_a"]:
        raise RuntimeError("reviews_a index signature changed")
    if fixture["indexes_b"] != original_b_indexes:
        raise RuntimeError("reviews_b index signature was not restored")
    if base.source_snapshot() != original_source:
        raise RuntimeError("source fixture signature changed")
    if index_exists():
        raise RuntimeError(f"temporary index remains: {C_INDEX}")
    return fixture


def run():
    global original_source, original_fixture, original_b_indexes
    if index_exists():
        raise RuntimeError(f"fail-closed: temporary index collision: {C_INDEX}")
    original_source = base.source_snapshot()
    original_fixture = base.verify_fixture()
    original_b_indexes = original_fixture["indexes_b"]
    if base.metadata() != f"{base.OWNER}\t{base.VERSION}\t{base.LOGICAL_SEED}\t{base.EXPECTED_ROWS}\t1":
        raise RuntimeError("review fixture is not the expected ready fixture")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    artifact = ROOT / "build/benchmark" / f"product-review-rating-filter-{stamp}"
    artifact.mkdir(parents=True, exist_ok=False)
    shutil.copy2(Path(__file__), artifact / "executed-harness.py")
    shutil.copy2(BASE_PATH, artifact / "base-fixture-verifier.py")
    (artifact / "query.sql").write_text(
        "SELECT id,rating,content,created_at FROM reviews_{a|b} "
        "WHERE product_id=? AND rating=1 ORDER BY created_at DESC,id DESC LIMIT 21;\n")
    (artifact / "plan.md").write_text("""# Low-rating latest-review index comparison (SQL only)

Uses the existing ready `bench_reviews` A/B fixture without reseeding or copying rows. A has PK + purchase UNIQUE + `product_id`; B has PK + purchase UNIQUE + `(product_id,created_at)`. C is B while one guarded temporary `(product_id,rating,created_at)` index is installed. The exact query is `WHERE product_id=? AND rating=1 ORDER BY created_at DESC,id DESC LIMIT 21`.

C is added only for C warm-up/measurements/EXPLAIN and dropped before B measurements; A is a separate table. Thus B is never measured while the optimizer can see C. Round 1 order is A→B→C and round 2 is C→B→A; only CREATE/DROP server time is excluded from query samples. No FORCE INDEX is used. One warm-up precedes two measured rounds; if any same-arm target spread exceeds 25%, that target gets one third A/B/C sample, then stops.

All page hashes and full ordered-result hashes are checked across arms. EXPLAIN and EXPLAIN ANALYZE are recorded per target/arm. The `finally` block may drop only this run's guarded C index; original A/B indexes, fixture rows, metadata, and source fixtures are verified afterward. Ratings are deterministic synthetic data, not an estimate of real review sentiment.
""")

    raw_samples = []
    counts = {}
    c_events_start = len(index_events)
    report = {"artifact": str(artifact.relative_to(ROOT)), "fixture_reused": True,
              "fixture_before": original_fixture, "source_before": original_source,
              "rating_distribution": {}, "targets": {}, "raw_samples": [],
              "c_index_events": index_events}
    try:
        rating_rows = base.lab.sql(
            "SELECT rating,COUNT(*) FROM reviews_a GROUP BY rating ORDER BY rating", SCHEMA)
        report["rating_distribution"] = {
            str(int(line.split("\t")[0])): int(line.split("\t")[1])
            for line in rating_rows.splitlines()}
        if report["rating_distribution"] != {str(r): 200000 for r in range(1, 6)}:
            raise RuntimeError(f"unexpected full rating distribution: {report['rating_distribution']}")
        for key, (product_id, total) in TARGETS.items():
            a_count = filtered_count("reviews_a", product_id)
            b_count = filtered_count("reviews_b", product_id)
            if a_count != b_count:
                raise RuntimeError(f"rating=1 count differs between A/B for {key}: {a_count}/{b_count}")
            counts[key] = a_count
            report["targets"][key] = {"product_id": product_id, "total_product_reviews": total,
                                      "rating_1_reviews": a_count, "rating_1_fraction": a_count / total}
        (artifact / "rating-counts.txt").write_text(json.dumps(report["targets"], indent=2) + "\n")

        # Warm all arms, with C installed only for the C arm.
        for design, table in (("A", "reviews_a"), ("B", "reviews_b")):
            for key, (product_id, _) in TARGETS.items():
                raw_samples.append(sample(table, key, product_id, counts[key], "warmup", design=design))
        set_c_index(True)
        try:
            for key, (product_id, _) in TARGETS.items():
                raw_samples.append(sample("reviews_b", key, product_id, counts[key], "warmup", design="C"))
        finally:
            if index_exists():
                set_c_index(False)

        record_plans(artifact, "A", "reviews_a")
        record_plans(artifact, "B", "reviews_b")
        record_plans(artifact, "C", "reviews_b", with_c_index=True)

        # Two counterbalanced measured rounds. Keep one index state for each arm group.
        for key, (product_id, _) in TARGETS.items():
            raw_samples.append(sample("reviews_a", key, product_id, counts[key], "measured", round_index=1, design="A"))
        for key, (product_id, _) in TARGETS.items():
            raw_samples.append(sample("reviews_b", key, product_id, counts[key], "measured", round_index=1, design="B"))
        set_c_index(True)
        try:
            for key, (product_id, _) in TARGETS.items():
                raw_samples.append(sample("reviews_b", key, product_id, counts[key], "measured", round_index=1, design="C"))
        finally:
            if index_exists():
                set_c_index(False)

        set_c_index(True)
        try:
            for key, (product_id, _) in TARGETS.items():
                raw_samples.append(sample("reviews_b", key, product_id, counts[key], "measured", round_index=2, design="C"))
        finally:
            if index_exists():
                set_c_index(False)
        for key, (product_id, _) in TARGETS.items():
            raw_samples.append(sample("reviews_b", key, product_id, counts[key], "measured", round_index=2, design="B"))
        for key, (product_id, _) in TARGETS.items():
            raw_samples.append(sample("reviews_a", key, product_id, counts[key], "measured", round_index=2, design="A"))

        adaptive_targets = []
        for key in TARGETS:
            values = {design: [x["server_elapsed_ms"] for x in raw_samples
                               if x.get("round") and x["target"] == key and x["design"] == design]
                      for design in ("A", "B", "C")}
            if any(max(v) / min(v) > 1.25 for v in values.values()):
                adaptive_targets.append(key)
        for key in adaptive_targets:
            product_id, _ = TARGETS[key]
            set_c_index(True)
            try:
                raw_samples.append(sample("reviews_b", key, product_id, counts[key], "measured",
                                          round_index=3, design="C"))
            finally:
                if index_exists():
                    set_c_index(False)
            raw_samples.append(sample("reviews_b", key, product_id, counts[key], "measured",
                                      round_index=3, design="B"))
            raw_samples.append(sample("reviews_a", key, product_id, counts[key], "measured",
                                      round_index=3, design="A"))

        results = {}
        c_full_results = {}
        set_c_index(True)
        try:
            for key, (product_id, _) in TARGETS.items():
                c_full_results[key] = full_result("reviews_b", product_id)
        finally:
            if index_exists():
                set_c_index(False)
        for key, (product_id, _) in TARGETS.items():
            a_full = full_result("reviews_a", product_id)
            b_full = full_result("reviews_b", product_id)
            # C is the same B table; its full result was captured with the temporary index visible.
            c_full = c_full_results[key]
            if a_full != b_full or b_full != c_full or a_full["rows"] != counts[key]:
                raise RuntimeError(f"full ordered rows/hash mismatch for {key}: A={a_full}, B={b_full}, C={c_full}")
            pages = {x["first_page_sha256"] for x in raw_samples if x["target"] == key}
            if len(pages) != 1:
                raise RuntimeError(f"first-page hash mismatch across samples for {key}: {pages}")
            values = {design: [x["server_elapsed_ms"] for x in raw_samples
                               if x.get("round") and x["target"] == key and x["design"] == design]
                      for design in ("A", "B", "C")}
            results[key] = dict(report["targets"][key])
            results[key].update({"full_ordered_rows": a_full["rows"],
                                 "full_ordered_sha256": a_full["sha256"],
                                 "first_page_hashes_equal": True,
                                 "samples_ms": values,
                                 "medians_ms": {d: statistics.median(v) for d, v in values.items()},
                                 "adaptive_third_round": key in adaptive_targets,
                                 "third_round_spread_still_over_25pct": (
                                     any(max(v) / min(v) > 1.25 for v in values.values())
                                     if key in adaptive_targets else None)})
        report["targets"] = results
        report["adaptive_third_round_targets"] = adaptive_targets
        report["raw_samples"] = raw_samples
        report["c_index_events"] = index_events[c_events_start:]
        report["source_after"] = base.source_snapshot()
        report["fixture_after"] = verify_restored()
        report["fixture_retained"] = True
        report["environment"] = {"mysql_version": base.lab.sql("SELECT VERSION()"),
                                  "buffer_pool_bytes": int(base.lab.sql("SELECT @@innodb_buffer_pool_size")),
                                  "sort_buffer_bytes": int(base.lab.sql("SELECT @@sort_buffer_size")),
                                  "server_time_zone": base.lab.sql("SELECT @@time_zone"),
                                  "query_timer": "server-side elapsed; Docker/mysql client startup excluded"}
        report["source_unchanged"] = report["source_after"] == original_source
        (artifact / "raw-and-summary.json").write_text(json.dumps(report, indent=2) + "\n")
        print(f"Artifacts: {artifact.relative_to(ROOT)}")
    except Exception as error:
        report["error"] = f"{type(error).__name__}: {error}"
        report["raw_samples"] = raw_samples
        report["c_index_events"] = index_events[c_events_start:]
        try:
            if index_exists():
                set_c_index(False)
            report["source_after"] = base.source_snapshot()
            report["fixture_after"] = verify_restored()
            report["fixture_retained"] = True
        except Exception as verify_error:
            report["cleanup_verify_error"] = f"{type(verify_error).__name__}: {verify_error}"
        (artifact / "raw-and-summary.json").write_text(json.dumps(report, indent=2) + "\n")
        raise
    finally:
        # Fail-safe: remove only the guarded C index absent before this run.
        if created_by_this_run and index_exists():
            set_c_index(False)


if __name__ == "__main__":
    try:
        run()
    except Exception as error:
        print(f"benchmark failed: {error}", file=sys.stderr)
        sys.exit(1)
