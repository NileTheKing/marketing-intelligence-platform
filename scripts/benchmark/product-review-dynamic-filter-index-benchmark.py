#!/usr/bin/env python3
"""SQL-only dynamic-filter review query comparison on a persistent synthetic snapshot."""

import argparse
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
TABLE = "review_attributes"
META_TABLE = "review_attribute_fixture_meta"
OWNER = "axon-review-dynamic-filter-benchmark"
VERSION = "1"
SEED = "review-attributes-v1; independent-modular-snapshot; 1000000 rows"
EXPECTED_ROWS = 1_000_000
TARGETS = base.TARGETS
CONDITION = "purchase_size='L' AND height_cm BETWEEN 175 AND 180 AND weight_kg BETWEEN 70 AND 80"
INDEXES = {
    "A": ("idx_review_attr_product", "product_id"),
    "B": ("idx_review_attr_filter", "product_id,purchase_size,height_cm,weight_kg,helpful_count"),
    "C": ("idx_review_attr_helpful", "product_id,purchase_size,helpful_count"),
}


def schema_table_exists(table):
    return base.lab.sql(
        "SELECT COUNT(*) FROM information_schema.tables "
        f"WHERE table_schema='{SCHEMA}' AND table_name='{table}'") == "1"


def metadata():
    return base.lab.sql(
        f"SELECT owner,version,logical_seed,row_count,ready FROM {META_TABLE} WHERE id=1", SCHEMA)


def attribute_index_signature():
    return base.lab.sql(
        "SELECT index_name,seq_in_index,column_name,non_unique FROM information_schema.statistics "
        f"WHERE table_schema='{SCHEMA}' AND table_name='{TABLE}' ORDER BY index_name,seq_in_index")


def expected_index_signature(arm):
    name, columns = INDEXES[arm]
    column_rows = "\n".join(f"{name}\t{i}\t{col}\t1" for i, col in enumerate(columns.split(","), 1))
    return f"{column_rows}\nPRIMARY\t1\treview_id\t0"


def current_arm():
    signature = attribute_index_signature()
    for arm in INDEXES:
        if signature == expected_index_signature(arm):
            return arm
    if signature == "PRIMARY\t1\treview_id\t0":
        return None
    raise RuntimeError(f"unknown review_attributes index signature; refusing DDL: {signature!r}")


def expected_columns():
    return ("review_id\tbigint\tNO\t\t\n"
            "product_id\tbigint\tNO\t\t\n"
            "purchase_size\tchar\tNO\t2\t\n"
            "height_cm\tsmallint\tNO\t\t\n"
            "weight_kg\tsmallint\tNO\t\t\n"
            "helpful_count\tint\tNO\t\t")


def column_signature():
    raw = base.lab.sql(
        "SELECT column_name,data_type,is_nullable,COALESCE(character_maximum_length,''),extra "
        "FROM information_schema.columns "
        f"WHERE table_schema='{SCHEMA}' AND table_name='{TABLE}' ORDER BY ordinal_position")
    return "\n".join(line.rstrip("\t") for line in raw.splitlines())


def verify_fixture():
    if not schema_table_exists(META_TABLE) or not schema_table_exists(TABLE):
        raise RuntimeError("review attribute fixture is absent or incomplete; refusing automatic reset")
    expected_meta = f"{OWNER}\t{VERSION}\t{SEED}\t{EXPECTED_ROWS}\t1"
    actual_meta = metadata()
    if actual_meta != expected_meta:
        raise RuntimeError(f"review attribute metadata mismatch/not ready: {actual_meta!r}")
    if column_signature() != "\n".join(line.rstrip("\t") for line in expected_columns().splitlines()):
        raise RuntimeError("review attribute column signature mismatch")
    count = int(base.lab.sql(f"SELECT COUNT(*) FROM {TABLE}", SCHEMA))
    if count != EXPECTED_ROWS:
        raise RuntimeError(f"review attribute count mismatch: {count}")
    map_mismatch = base.lab.sql(
        f"SELECT COUNT(*) FROM {TABLE} a LEFT JOIN reviews_b r ON r.id=a.review_id "
        "WHERE r.id IS NULL OR r.product_id<>a.product_id", SCHEMA)
    if map_mismatch != "0":
        raise RuntimeError(f"review attribute to review mapping mismatch: {map_mismatch}")
    return {"metadata": actual_meta, "rows": count,
            "columns": column_signature(), "index_signature": attribute_index_signature(),
            "active_arm": current_arm()}


def seed_sql():
    return """INSERT INTO review_attributes(review_id,product_id,purchase_size,height_cm,weight_kg,helpful_count)
SELECT p.id,p.product_id,
 CASE
  WHEN p.weight_kg>75 THEN CASE WHEN p.size_roll<5 THEN 'XS' WHEN p.size_roll<18 THEN 'S' WHEN p.size_roll<43 THEN 'M' WHEN p.size_roll<78 THEN 'L' ELSE 'XL' END
  WHEN p.weight_kg<60 THEN CASE WHEN p.size_roll<18 THEN 'XS' WHEN p.size_roll<42 THEN 'S' WHEN p.size_roll<70 THEN 'M' WHEN p.size_roll<92 THEN 'L' ELSE 'XL' END
  ELSE CASE WHEN p.size_roll<10 THEN 'XS' WHEN p.size_roll<30 THEN 'S' WHEN p.size_roll<60 THEN 'M' WHEN p.size_roll<85 THEN 'L' ELSE 'XL' END
 END,
 p.height_cm,p.weight_kg,
 CASE WHEN p.help_roll<700 THEN 0
      WHEN p.help_roll<900 THEN 1+MOD(p.help_value,4)
      WHEN p.help_roll<980 THEN 5+MOD(p.help_value,46)
      ELSE 51+MOD(p.help_value,950) END
FROM (
 SELECT id,product_id,
        150+MOD(id*7919+FLOOR(id/37),46) AS height_cm,
        50+FLOOR((150+MOD(id*7919+FLOOR(id/37),46)-150)*0.62)
          +MOD(id*104729+FLOOR(id/113),17)-8 AS weight_kg,
        MOD(id*3571+FLOOR(id/97),100) AS size_roll,
        MOD(id*6151+FLOOR(id/43),1000) AS help_roll,
        MOD(id*104729+FLOOR(id/89),1000) AS help_value
 FROM reviews_b
) p
ORDER BY p.id"""


def expected_summaries():
    return {
        "size_counts": base.lab.sql(
            f"SELECT purchase_size,COUNT(*) FROM {TABLE} GROUP BY purchase_size ORDER BY purchase_size", SCHEMA),
        "body_ranges": base.lab.sql(
            f"SELECT MIN(height_cm),MAX(height_cm),ROUND(AVG(height_cm),2),MIN(weight_kg),MAX(weight_kg),ROUND(AVG(weight_kg),2) FROM {TABLE}", SCHEMA),
        "helpful_distribution": base.lab.sql(
            f"SELECT helpful_count=0,COUNT(*) FROM {TABLE} GROUP BY helpful_count=0 ORDER BY helpful_count=0", SCHEMA),
        "height_bands": base.lab.sql(
            f"SELECT FLOOR(height_cm/10)*10,COUNT(*) FROM {TABLE} GROUP BY FLOOR(height_cm/10)*10 ORDER BY 1", SCHEMA),
        "weight_bands": base.lab.sql(
            f"SELECT FLOOR(weight_kg/10)*10,COUNT(*) FROM {TABLE} GROUP BY FLOOR(weight_kg/10)*10 ORDER BY 1", SCHEMA),
    }


def create_fixture(artifact):
    meta_exists = schema_table_exists(META_TABLE)
    table_exists = schema_table_exists(TABLE)
    if meta_exists or table_exists:
        if not (meta_exists and table_exists):
            raise RuntimeError("partial review attribute fixture exists; fail closed")
        fixture = verify_fixture()
        # Recover only a known arm left by an interrupted run; never reseed existing rows.
        switch_arm("A", allow_known_arm=True)
        return True, {}, verify_fixture()

    setup = {}
    base.lab.sql(
        f"CREATE TABLE {META_TABLE} (id INT PRIMARY KEY, owner VARCHAR(80) NOT NULL, "
        "version VARCHAR(20) NOT NULL, logical_seed VARCHAR(120) NOT NULL, "
        "row_count BIGINT NOT NULL, ready BOOLEAN NOT NULL, created_at DATETIME(6) NOT NULL)", SCHEMA)
    base.lab.sql(
        f"INSERT INTO {META_TABLE} VALUES (1,'{OWNER}','{VERSION}','{SEED}',{EXPECTED_ROWS},false,NOW(6))", SCHEMA)
    setup["create_attribute_table_ms"] = base.timed_sql(
        f"CREATE TABLE {TABLE} (review_id BIGINT NOT NULL, product_id BIGINT NOT NULL, "
        "purchase_size CHAR(2) NOT NULL, height_cm SMALLINT NOT NULL, weight_kg SMALLINT NOT NULL, "
        "helpful_count INT NOT NULL, PRIMARY KEY(review_id)) ENGINE=InnoDB")
    setup["seed_ordered_by_review_id_ms"] = base.timed_sql(seed_sql())
    setup["create_index_a_ms"] = base.timed_sql(
        f"CREATE INDEX {INDEXES['A'][0]} ON {TABLE}({INDEXES['A'][1]})")
    setup["analyze_table_ms"] = base.timed_sql(f"ANALYZE TABLE {TABLE}")
    if int(base.lab.sql(f"SELECT COUNT(*) FROM {TABLE}", SCHEMA)) != EXPECTED_ROWS:
        raise RuntimeError("review attribute seed count mismatch; table retained unready")
    if base.lab.sql(
        f"SELECT COUNT(*) FROM {TABLE} a LEFT JOIN reviews_b r ON r.id=a.review_id "
        "WHERE r.id IS NULL OR r.product_id<>a.product_id", SCHEMA) != "0":
        raise RuntimeError("review attribute seed mapping mismatch; table retained unready")
    if current_arm() != "A":
        raise RuntimeError("review attribute seed A index signature mismatch")
    base.lab.sql(f"UPDATE {META_TABLE} SET ready=true WHERE id=1", SCHEMA)
    return False, setup, verify_fixture()


def switch_arm(arm, allow_known_arm=False):
    current = current_arm()
    if current is None and not allow_known_arm:
        # A no-index state is accepted only as a transition generated by this private harness.
        pass
    events = []
    if current == arm:
        return events
    if current is not None:
        name = INDEXES[current][0]
        elapsed = base.timed_sql(f"DROP INDEX {name} ON {TABLE}")
        events.append({"action": "drop", "arm": current, "server_elapsed_ms": elapsed})
    name, columns = INDEXES[arm]
    elapsed = base.timed_sql(f"CREATE INDEX {name} ON {TABLE}({columns})")
    events.append({"action": "create", "arm": arm, "server_elapsed_ms": elapsed})
    if current_arm() != arm:
        raise RuntimeError(f"index switch to {arm} did not produce the expected signature")
    return events


def query(product_id, limit=21):
    suffix = f" LIMIT {limit}" if limit is not None else ""
    return ("SELECT r.id,r.rating,r.content,r.created_at,a.helpful_count "
            f"FROM {TABLE} a JOIN reviews_b r ON r.id=a.review_id "
            f"WHERE a.product_id={product_id} AND {CONDITION} "
            "ORDER BY a.helpful_count DESC,a.review_id DESC" + suffix)


def sample_query(product_id, expected_count):
    output = base.lab.sql(
        "SET @started=SYSDATE(6);" + query(product_id)
        + ";SELECT 'QUERY_MS',TIMESTAMPDIFF(MICROSECOND,@started,SYSDATE(6))/1000", SCHEMA)
    lines = output.splitlines()
    elapsed = float(lines[-1].split("\t")[1])
    rows = lines[:-1]
    expected_page = min(21, expected_count)
    if len(rows) != expected_page:
        raise RuntimeError(f"product {product_id}: expected {expected_page} rows, got {len(rows)}")
    return {"server_elapsed_ms": elapsed, "first_page_rows": len(rows),
            "first_page_sha256": hashlib.sha256("\n".join(rows).encode()).hexdigest()}


def full_result(product_id):
    rows = base.lab.sql(query(product_id, limit=None), SCHEMA).splitlines()
    return {"rows": len(rows), "sha256": hashlib.sha256("\n".join(rows).encode()).hexdigest()}


def status():
    source = base.source_snapshot()
    reviews = base.verify_fixture()
    if not schema_table_exists(META_TABLE) or not schema_table_exists(TABLE):
        raise RuntimeError("review attribute fixture is absent or incomplete")
    attributes = verify_fixture()
    print(json.dumps({"source": source, "reviews": reviews,
                      "attribute_fixture": attributes,
                      "attribute_index_arm": current_arm()}, indent=2))


def main_run():
    if base.metadata() != f"{base.OWNER}\t{base.VERSION}\t{base.LOGICAL_SEED}\t{base.EXPECTED_ROWS}\t1":
        raise RuntimeError("base review fixture is not ready or has unexpected metadata")
    source_before = base.source_snapshot()
    review_before = base.verify_fixture()
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    artifact = ROOT / "build/benchmark" / f"product-review-dynamic-filter-{stamp}"
    artifact.mkdir(parents=True, exist_ok=False)
    shutil.copy2(Path(__file__), artifact / "executed-harness.py")
    shutil.copy2(BASE_PATH, artifact / "base-fixture-verifier.py")
    (artifact / "schema-and-indexes.sql").write_text(
        "CREATE TABLE review_attributes (review_id BIGINT PRIMARY KEY, product_id BIGINT NOT NULL, "
        "purchase_size CHAR(2) NOT NULL, height_cm SMALLINT NOT NULL, weight_kg SMALLINT NOT NULL, "
        "helpful_count INT NOT NULL);\n"
        + "\n".join(f"CREATE INDEX {name} ON review_attributes({columns});" for name, columns in INDEXES.values()) + "\n")
    (artifact / "seed.sql").write_text(seed_sql() + ";\n")
    (artifact / "query.sql").write_text(query(1) + ";\n")
    (artifact / "plan.md").write_text("""# Dynamic-filter product review query (SQL only)

The existing `bench_reviews.reviews_a/reviews_b` 1M-row data is read-only. This experiment adds a persistent, guarded `review_attributes` snapshot keyed by the matching review id, without copying review content. Product id is projected into the synthetic snapshot to model a review-time attribute side table; this projection is not an implemented Axon entity or API. Size, height, weight, and helpful count are deterministic synthetic review-time attributes. Height and weight have a bounded positive association; size likelihood varies by weight band; helpful count is independent and zero-heavy. They are not real store/user distributions.

All arms run the same join to `reviews_b`: filters product id, L, height 175–180 inclusive, weight 70–80 inclusive; orders helpful_count DESC, review_id DESC; returns at most 21 detail rows. A has only product_id; B has product/size/height/weight/helpful; C has product/size/helpful. Only one arm's secondary index is present at query time; index DDL timing is recorded separately. No FORCE INDEX is used. Round order is ABC then CBA, with one additional ABC sample only for targets whose same-arm spread exceeds 25% after two samples.

A missing fixture is seeded once in ascending review_id order. A present fixture is validated/reused, never reset or reseeded; only recognized experiment indexes can be switched. After the run A is restored as canonical. The report stores source and review signatures/counts, actual attribute distribution, query samples/hashes, EXPLAIN and EXPLAIN ANALYZE actual rows, environment, and DDL timing.
""")

    setup = {}
    ddl_events = []
    raw = []
    created_fixture = False
    report = {"fixture_reused": None, "setup_timings_ms": {}, "source_before": source_before,
              "reviews_before": review_before, "targets": {}, "raw_samples": [],
              "index_ddl_events": ddl_events}
    try:
        reused, setup, attr_fixture = create_fixture(artifact)
        created_fixture = not reused
        report["fixture_reused"] = reused
        report["setup_timings_ms"] = setup
        report["attribute_fixture_after_setup"] = attr_fixture
        report["source_snapshot_unchanged_before_after_setup"] = base.source_snapshot() == source_before
        # Distribution evidence before choosing interpretation of the query results.
        report["attribute_distributions"] = expected_summaries()
        rating1 = int(base.lab.sql(
            f"SELECT COUNT(*) FROM {TABLE} WHERE {CONDITION}", SCHEMA))
        if rating1 < 0:
            raise RuntimeError("impossible matching count")
        target_counts = {}
        for key, (product_id, total) in TARGETS.items():
            target_counts[key] = int(base.lab.sql(
                f"SELECT COUNT(*) FROM {TABLE} WHERE product_id={product_id} AND {CONDITION}", SCHEMA))
            report["targets"][key] = {"product_id": product_id, "total_product_reviews": total,
                                      "matching_reviews": target_counts[key],
                                      "matching_fraction": target_counts[key] / total}
        (artifact / "attribute-distributions.json").write_text(
            json.dumps({"all_matches": rating1, "targets": report["targets"],
                        "distributions": report["attribute_distributions"]}, indent=2) + "\n")

        for arm in "ABC":
            ddl_events.extend(switch_arm(arm))
            for key, (product_id, _) in TARGETS.items():
                (artifact / f"explain-{key}-{arm}.txt").write_text(
                    base.lab.sql("EXPLAIN " + query(product_id), SCHEMA) + "\n")
                (artifact / f"explain-analyze-{key}-{arm}.txt").write_text(
                    base.lab.sql("EXPLAIN ANALYZE " + query(product_id), SCHEMA) + "\n")
        ddl_events.extend(switch_arm("A"))

        # Warm-up in canonical ABC order, then counterbalanced measured rounds ABC / CBA.
        for arm in "ABC":
            ddl_events.extend(switch_arm(arm))
            table = TABLE
            for key, (product_id, _) in TARGETS.items():
                sample = sample_query(product_id, target_counts[key])
                raw.append({"phase": "warmup", "arm": arm, "target": key, **sample})
        ddl_events.extend(switch_arm("A"))
        for round_index, order in ((1, "ABC"), (2, "CBA")):
            for arm in order:
                ddl_events.extend(switch_arm(arm))
                for key, (product_id, _) in TARGETS.items():
                    sample = sample_query(product_id, target_counts[key])
                    raw.append({"phase": "measured", "round": round_index, "arm": arm,
                                "target": key, **sample})

        adaptive = []
        for key in TARGETS:
            samples = {arm: [x["server_elapsed_ms"] for x in raw
                            if x.get("phase") == "measured" and x["target"] == key and x["arm"] == arm]
                       for arm in "ABC"}
            if any(max(values) / min(values) > 1.25 for values in samples.values()):
                adaptive.append(key)
        for key in adaptive:
            product_id, _ = TARGETS[key]
            for arm in "ABC":
                ddl_events.extend(switch_arm(arm))
                sample = sample_query(product_id, target_counts[key])
                raw.append({"phase": "measured", "round": 3, "arm": arm, "target": key, **sample})

        # Full result equality for each arm, after all arm-specific indexes are selected.
        full = {}
        for arm in "ABC":
            ddl_events.extend(switch_arm(arm))
            for key, (product_id, _) in TARGETS.items():
                full[(arm, key)] = full_result(product_id)
        for key in TARGETS:
            arm_hashes = {full[(arm, key)]["sha256"] for arm in "ABC"}
            row_counts = {full[(arm, key)]["rows"] for arm in "ABC"}
            page_hashes = {x["first_page_sha256"] for x in raw if x["target"] == key}
            if len(arm_hashes) != 1 or len(row_counts) != 1 or len(page_hashes) != 1:
                raise RuntimeError(f"ordered result/page hash mismatch for {key}")
            values = {arm: [x["server_elapsed_ms"] for x in raw
                            if x.get("phase") == "measured" and x["target"] == key and x["arm"] == arm]
                      for arm in "ABC"}
            report["targets"][key].update({
                "full_match_rows": next(iter(row_counts)),
                "full_ordered_sha256": next(iter(arm_hashes)),
                "first_page_hashes_equal": True,
                "samples_ms": values,
                "medians_ms": {arm: statistics.median(v) for arm, v in values.items()},
                "adaptive_third_round": key in adaptive,
                "third_round_spread_still_over_25pct": (
                    any(max(v) / min(v) > 1.25 for v in values.values()) if key in adaptive else None)})

        # Restore the documented persistent fixture state: A, one product_id index only.
        ddl_events.extend(switch_arm("A"))
        final_attr = verify_fixture()
        if final_attr["active_arm"] != "A":
            raise RuntimeError("failed to restore canonical A attribute index")
        source_after = base.source_snapshot()
        reviews_after = base.verify_fixture()
        if source_after != source_before or reviews_after != review_before:
            raise RuntimeError("source or existing review fixture changed")
        report.update({"adaptive_third_round_targets": adaptive, "raw_samples": raw,
                       "index_ddl_events": ddl_events, "attribute_fixture_final": final_attr,
                       "source_after": source_after, "reviews_after": reviews_after,
                       "source_unchanged": True, "existing_reviews_unchanged": True,
                       "canonical_index_restored": True,
                       "environment": {"mysql_version": base.lab.sql("SELECT VERSION()"),
                                       "buffer_pool_bytes": int(base.lab.sql("SELECT @@innodb_buffer_pool_size")),
                                       "sort_buffer_bytes": int(base.lab.sql("SELECT @@sort_buffer_size")),
                                       "server_time_zone": base.lab.sql("SELECT @@time_zone"),
                                       "query_timer": "server elapsed; Docker/mysql client startup excluded"}})
        (artifact / "raw-and-summary.json").write_text(json.dumps(report, indent=2) + "\n")
        print(f"Artifacts: {artifact.relative_to(ROOT)}")
    except Exception as error:
        report["error"] = f"{type(error).__name__}: {error}"
        report["raw_samples"] = raw
        report["index_ddl_events"] = ddl_events
        try:
            ddl_events.extend(switch_arm("A", allow_known_arm=True))
            report["attribute_fixture_after_error"] = verify_fixture()
            report["source_after"] = base.source_snapshot()
            report["reviews_after"] = base.verify_fixture()
        except Exception as cleanup_error:
            report["cleanup_verify_error"] = f"{type(cleanup_error).__name__}: {cleanup_error}"
        (artifact / "raw-and-summary.json").write_text(json.dumps(report, indent=2) + "\n")
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("run", "status"), nargs="?", default="run")
    args = parser.parse_args()
    try:
        if args.command == "status":
            status()
        else:
            main_run()
    except Exception as error:
        print(f"dynamic filter benchmark failed: {error}", file=sys.stderr)
        sys.exit(1)
