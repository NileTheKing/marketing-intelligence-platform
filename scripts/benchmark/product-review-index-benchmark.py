#!/usr/bin/env python3
"""Persistent, guarded SQL-only product-review index comparison."""

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
LAB_PATH = ROOT / "scripts/benchmark/mysql-lab.py"
spec = importlib.util.spec_from_file_location("mysql_lab", LAB_PATH)
lab = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = lab
spec.loader.exec_module(lab)

SCHEMA = "bench_reviews"
OWNER = "axon-product-review-index-benchmark"
VERSION = "1"
LOGICAL_SEED = "review-fixture-v1; logical-id=1+mod(n*7919,1000000)"
EXPECTED_ROWS = 1_000_000
TARGETS = {"small_100": (951, 100), "medium_1000": (11, 1000), "popular_10000": (1, 10000)}
OLD_FULL_HASHES = {
    "small_100": "02b8afdf550c96c621e2cb60a74d77de355ada05288558de7832f76ea2931761",
    "medium_1000": "318871091b640883505443ff5c3a3992a79e108f89cff8f45e9de810d391c4a6",
    "popular_10000": "9e38413781831c48de0a72fbddabbc876cc27c3e947eeb27efdd60512b1b2194",
}


def sql_identifier(value):
    parts = value.split(".")
    if any(not part.replace("_", "").isalnum() for part in parts):
        raise ValueError("unsafe generated SQL identifier")
    return ".".join(f"`{part}`" for part in parts)


def source_snapshot():
    lab.verify_lab()
    result = {}
    for database, table in (("bench_purchases", "purchases"),
                            ("bench_candidates", "abandoned_cart_coupon_candidates")):
        meta = lab.sql("SELECT version,row_count,anchor_at,ready FROM _fixture", database)
        count = lab.sql(f"SELECT COUNT(*) FROM {table}", database)
        indexes = lab.sql(
            "SELECT index_name,seq_in_index,column_name,non_unique FROM information_schema.statistics "
            f"WHERE table_schema='{database}' AND table_name='{table}' ORDER BY index_name,seq_in_index")
        expected = "1\t1000000\t2026-10-14 12:00:00\t1" if database == "bench_purchases" \
            else "1\t5000000\t2026-10-14 12:00:00\t1"
        if meta != expected or count != expected.split("\t")[1]:
            raise RuntimeError(f"unexpected source fixture {database}: metadata={meta}, count={count}")
        result[database] = {"metadata": meta, "rows": int(count), "indexes": indexes}
    return result


def schema_exists():
    return lab.sql(f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{SCHEMA}'") == "1"


def metadata():
    return lab.sql("SELECT owner,version,logical_seed,row_count,ready FROM review_fixture_meta WHERE id=1", SCHEMA)


def index_signature(table):
    return lab.sql("SELECT index_name,seq_in_index,column_name,non_unique FROM information_schema.statistics "
                   f"WHERE table_schema='{SCHEMA}' AND table_name='{table}' ORDER BY index_name,seq_in_index")


def expected_index_signature(design):
    if design == "A":
        return ("idx_product_review_product\t1\tproduct_id\t1\n"
                "PRIMARY\t1\tid\t0\n"
                "uk_product_review_purchase\t1\tpurchase_id\t0")
    return ("idx_product_review_product_created\t1\tproduct_id\t1\n"
            "idx_product_review_product_created\t2\tcreated_at\t1\n"
            "PRIMARY\t1\tid\t0\n"
            "uk_product_review_purchase\t1\tpurchase_id\t0")


def column_signature(table):
    return lab.sql("SELECT column_name,data_type,is_nullable,COALESCE(character_maximum_length,''),extra "
                   "FROM information_schema.columns "
                   f"WHERE table_schema='{SCHEMA}' AND table_name='{table}' ORDER BY ordinal_position")


def expected_column_signature():
    return ("id\tbigint\tNO\t\tauto_increment\n"
            "product_id\tbigint\tNO\t\t\n"
            "purchase_id\tbigint\tNO\t\t\n"
            "user_id\tbigint\tNO\t\t\n"
            "rating\tint\tNO\t\t\n"
            "content\tvarchar\tNO\t1000\t\n"
            "created_at\tdatetime\tNO\t")


def normalized_column_signature(signature):
    # mysql-lab.sql strips outer whitespace, including the final empty `extra` field delimiter.
    return "\n".join(line.rstrip("\t") for line in signature.splitlines())


def verify_fixture():
    if not schema_exists():
        raise RuntimeError(f"persistent review schema {SCHEMA} is absent; run this script once to seed")
    expected_meta = f"{OWNER}\t{VERSION}\t{LOGICAL_SEED}\t{EXPECTED_ROWS}\t1"
    actual_meta = metadata()
    if actual_meta != expected_meta:
        raise RuntimeError(f"review fixture metadata collision or not ready: {actual_meta!r}")
    expected_columns = expected_column_signature()
    for design, table in (("A", "reviews_a"), ("B", "reviews_b")):
        count = lab.sql(f"SELECT COUNT(*) FROM {table}", SCHEMA)
        if count != str(EXPECTED_ROWS):
            raise RuntimeError(f"{table} row count differs: {count}")
        if index_signature(table) != expected_index_signature(design):
            raise RuntimeError(f"{table} index signature differs from {design}")
        if normalized_column_signature(column_signature(table)) != normalized_column_signature(expected_columns):
            raise RuntimeError(f"{table} column signature differs from the application review mapping")
    for key, (product_id, expected) in TARGETS.items():
        count = lab.sql(f"SELECT COUNT(*) FROM reviews_a WHERE product_id={product_id}", SCHEMA)
        if count != str(expected):
            raise RuntimeError(f"{key} distribution mismatch: expected {expected}, got {count}")
    return {"metadata": actual_meta,
            "rows_a": EXPECTED_ROWS,
            "rows_b": EXPECTED_ROWS,
            "indexes_a": index_signature("reviews_a"),
            "indexes_b": index_signature("reviews_b"),
            "columns": expected_columns}


def timed_sql(statement):
    output = lab.sql("SET @review_started=SYSDATE(6);" + statement
                     + ";SELECT 'REVIEW_SETUP_MS',"
                       "TIMESTAMPDIFF(MICROSECOND,@review_started,SYSDATE(6))/1000", SCHEMA)
    lines = output.splitlines()
    return float(lines[-1].split("\t")[1])


def insert_a_sql():
    return """INSERT INTO reviews_a(id,product_id,purchase_id,user_id,rating,content,created_at)
SELECT seq+1,
 CASE WHEN n<10000 THEN 1
      WHEN n<55000 THEN 2+FLOOR((n-10000)/5000)
      WHEN n<995000 THEN 11+FLOOR((n-55000)/1000)
      ELSE 951+FLOOR((n-995000)/100) END,
 800000000+n,900000000+MOD(n*31,400000),1+MOD(n*17,5),
 RPAD(CONCAT('The fit is accurate and the material feels comfortable. ',
             'Stitching and color match the product description. '),100+MOD(n*31,201),' '),
 CASE WHEN MOD(n,20)=0 THEN '2025-06-15 12:00:00.000000'
      ELSE DATE_ADD(DATE_ADD('2024-01-01 00:00:00',INTERVAL MOD(n*7919,63072000) SECOND),
                    INTERVAL MOD(n*997,1000000) MICROSECOND) END
FROM (SELECT a.n*1000+b.n AS seq, MOD((a.n*1000+b.n)*17679,1000000) AS n
      FROM bench_purchases._seq a CROSS JOIN bench_purchases._seq b) numbers
ORDER BY seq"""


def query(table, product_id, limit=None):
    suffix = f" LIMIT {limit}" if limit is not None else ""
    return (f"SELECT id,rating,content,created_at FROM {table} "
            f"WHERE product_id={product_id} ORDER BY created_at DESC,id DESC{suffix}")


def sample_query(table, product_id):
    output = lab.sql("SET @review_started=SYSDATE(6);" + query(table, product_id, 21)
                     + ";SELECT 'REVIEW_ELAPSED_MS',"
                       "TIMESTAMPDIFF(MICROSECOND,@review_started,SYSDATE(6))/1000", SCHEMA)
    lines = output.splitlines()
    elapsed = float(lines[-1].split("\t")[1])
    rows = lines[:-1]
    if len(rows) != 21:
        raise RuntimeError(f"Expected first 21 rows for product {product_id}, got {len(rows)}")
    return elapsed, hashlib.sha256("\n".join(rows).encode()).hexdigest()


def full_result(table, product_id):
    rows = lab.sql(query(table, product_id), SCHEMA).splitlines()
    return len(rows), hashlib.sha256("\n".join(rows).encode()).hexdigest()


def record_artifact(artifact, source_before, reuse, setup, raw_samples, results=None, source_after=None):
    report = {"schema": SCHEMA, "owner": OWNER, "version": VERSION,
              "source_before": source_before, "source_after": source_after,
              "source_unchanged": source_after == source_before if source_after is not None else None,
              "fixture_reused": reuse, "setup_timings_ms": setup, "raw_samples": raw_samples,
              "targets": results or {},
              "environment": {"mysql_version": lab.sql("SELECT VERSION()"),
                              "buffer_pool_bytes": int(lab.sql("SELECT @@innodb_buffer_pool_size")),
                              "sort_buffer_bytes": int(lab.sql("SELECT @@sort_buffer_size")),
                              "server_time_zone": lab.sql("SELECT @@time_zone"),
                              "loopback": "127.0.0.1:13306",
                              "query_timer": "MySQL server elapsed time; Docker/mysql client startup excluded"}}
    (artifact / "raw-and-summary.json").write_text(json.dumps(report, indent=2) + "\n")
    return report


def fixture_sql_artifacts(artifact):
    (artifact / "schema.sql").write_text("""CREATE DATABASE bench_reviews;
CREATE TABLE review_fixture_meta (
 id INT PRIMARY KEY, owner VARCHAR(80) NOT NULL, version VARCHAR(20) NOT NULL,
 logical_seed VARCHAR(120) NOT NULL, row_count BIGINT NOT NULL, ready BOOLEAN NOT NULL,
 created_at DATETIME(6) NOT NULL);
CREATE TABLE reviews_a (
 id BIGINT NOT NULL AUTO_INCREMENT, product_id BIGINT NOT NULL, purchase_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL, rating INT NOT NULL, content VARCHAR(1000) NOT NULL,
 created_at DATETIME(6) NOT NULL, PRIMARY KEY(id));
CREATE TABLE reviews_b LIKE reviews_a;
CREATE UNIQUE INDEX uk_product_review_purchase ON reviews_a(purchase_id);
CREATE INDEX idx_product_review_product ON reviews_a(product_id);
CREATE UNIQUE INDEX uk_product_review_purchase ON reviews_b(purchase_id);
CREATE INDEX idx_product_review_product_created ON reviews_b(product_id,created_at);
""")
    (artifact / "seed-reviews-a.sql").write_text(insert_a_sql() + ";\n")
    (artifact / "seed-reviews-b.sql").write_text("INSERT INTO reviews_b(id,product_id,purchase_id,user_id,rating,content,created_at) "
                                                 "SELECT id,product_id,purchase_id,user_id,rating,content,created_at "
                                                 "FROM reviews_a ORDER BY id;\n")
    (artifact / "query.sql").write_text(query("reviews_a", 1, 21) + ";\n")


def create_fixture(artifact):
    # Schema absence is the only implicit seed path; existing/unready schemas always fail closed.
    if schema_exists():
        return True, {}
    if lab.sql("SELECT lab_id FROM benchmark_lab.identity WHERE id=1") != "axon-local-sql-lab":
        raise RuntimeError("dedicated MySQL Lab identity verification failed")
    timings = {}
    lab.sql(f"CREATE DATABASE {SCHEMA} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci", "mysql")
    lab.sql("CREATE TABLE review_fixture_meta (id INT PRIMARY KEY, owner VARCHAR(80) NOT NULL, "
            "version VARCHAR(20) NOT NULL, logical_seed VARCHAR(120) NOT NULL, row_count BIGINT NOT NULL, "
            "ready BOOLEAN NOT NULL, created_at DATETIME(6) NOT NULL)", SCHEMA)
    lab.sql("INSERT INTO review_fixture_meta VALUES "
            f"(1,'{OWNER}','{VERSION}','{LOGICAL_SEED}',{EXPECTED_ROWS},false,NOW(6))", SCHEMA)
    table_ddl = ("CREATE TABLE {name} (id BIGINT NOT NULL AUTO_INCREMENT, product_id BIGINT NOT NULL, "
                 "purchase_id BIGINT NOT NULL, user_id BIGINT NOT NULL, rating INT NOT NULL, "
                 "content VARCHAR(1000) NOT NULL, created_at DATETIME(6) NOT NULL, PRIMARY KEY(id)) ENGINE=InnoDB")
    timings["create_table_a_ms"] = timed_sql(table_ddl.format(name="reviews_a"))
    timings["create_table_b_ms"] = timed_sql(table_ddl.format(name="reviews_b"))
    timings["seed_a_sequential_pk_ms"] = timed_sql(insert_a_sql())
    timings["seed_b_ordered_copy_ms"] = timed_sql(
        "INSERT INTO reviews_b(id,product_id,purchase_id,user_id,rating,content,created_at) "
        "SELECT id,product_id,purchase_id,user_id,rating,content,created_at FROM reviews_a ORDER BY id")
    timings["a_unique_purchase_index_ms"] = timed_sql(
        "CREATE UNIQUE INDEX uk_product_review_purchase ON reviews_a(purchase_id)")
    timings["a_product_index_ms"] = timed_sql(
        "CREATE INDEX idx_product_review_product ON reviews_a(product_id)")
    timings["b_unique_purchase_index_ms"] = timed_sql(
        "CREATE UNIQUE INDEX uk_product_review_purchase ON reviews_b(purchase_id)")
    timings["b_product_created_index_ms"] = timed_sql(
        "CREATE INDEX idx_product_review_product_created ON reviews_b(product_id,created_at)")
    timings["analyze_ms"] = timed_sql("ANALYZE TABLE reviews_a,reviews_b")
    verify_fixture_without_ready()
    lab.sql("UPDATE review_fixture_meta SET ready=true WHERE id=1", SCHEMA)
    return False, timings


def verify_fixture_without_ready():
    # During the first seed only, verify all shape/count invariants before marking ready.
    for design, table in (("A", "reviews_a"), ("B", "reviews_b")):
        if lab.sql(f"SELECT COUNT(*) FROM {table}", SCHEMA) != str(EXPECTED_ROWS):
            raise RuntimeError(f"{table} seed row count mismatch")
        if index_signature(table) != expected_index_signature(design):
            raise RuntimeError(f"{table} index signature mismatch")
        if normalized_column_signature(column_signature(table)) != normalized_column_signature(expected_column_signature()):
            raise RuntimeError(f"{table} column signature mismatch")
    for product_id, expected in ((951, 100), (11, 1000), (1, 10000)):
        if lab.sql(f"SELECT COUNT(*) FROM reviews_a WHERE product_id={product_id}", SCHEMA) != str(expected):
            raise RuntimeError(f"product distribution mismatch for {product_id}")
    if lab.sql("SELECT COUNT(*),MIN(CHAR_LENGTH(content)),MAX(CHAR_LENGTH(content)) FROM reviews_a", SCHEMA) \
            != "1000000\t100\t300":
        raise RuntimeError("review body lengths/count differ from expected")


def status():
    source = source_snapshot()
    if not schema_exists():
        print(json.dumps({"review_schema": "absent", "source": source}, indent=2))
        return
    review = verify_fixture()
    print(json.dumps({"review_fixture": review, "source": source}, indent=2))


def run():
    source_before = source_snapshot()
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    artifact = ROOT / "build/benchmark" / f"product-review-index-reuse-{stamp}"
    artifact.mkdir(parents=True, exist_ok=False)
    fixture_sql_artifacts(artifact)
    shutil.copy2(Path(__file__), artifact / "executed-harness.py")
    plan = """# Product review index comparison (SQL only)

- Persistent guarded schema: bench_reviews. It is created and seeded once only if absent. Existing schema must have the exact owner/version/ready marker, row counts, application column types and A/B index signatures or the harness fails closed. There is no automatic drop/reset path. Ready fixture is retained for future runs.
- 1,000,000 deterministic logical reviews across 1,000 synthetic products: 1 x 10,000; 9 x 5,000; 940 x 1,000; 50 x 100. This is an experiment assumption, not a store-wide estimate. Text is 100-300 characters, timestamps span two years with deliberate ties, and modular permutation mixes product rows across ascending primary keys.
- A uses PK + UNIQUE(purchase_id) + single product_id. B uses the same PK/unique and `(product_id,created_at)` instead of product_id alone. Both use INT rating and VARCHAR(1000) content. Synthetic user/purchase ids and no purchase FK mean this is SQL-only.
- Every target uses identical full-field SQL with product filter, `ORDER BY created_at DESC,id DESC LIMIT 21`. Server-side elapsed time excludes Docker/mysql client startup. Hash the full ordered result and every page sample for equivalence.
- One warm-up plus two measured AB/BA rounds. If either design varies by >25% across the two samples for a target, take one third sample for that target only, independent of A/B effect size; reassess after three and do not automatically add a fourth.
"""
    (artifact / "plan.md").write_text(plan)
    setup = {}
    raw_samples = []
    results = {}
    try:
        reused, setup = create_fixture(artifact)
        fixture = verify_fixture()
        for key, (product_id, expected) in TARGETS.items():
            actual = int(lab.sql(f"SELECT COUNT(*) FROM reviews_a WHERE product_id={product_id}", SCHEMA))
            if actual != expected:
                raise RuntimeError(f"{key} distribution mismatch: {actual}")
            for design, table in (("A", "reviews_a"), ("B", "reviews_b")):
                elapsed, digest = sample_query(table, product_id)
                raw_samples.append({"phase": "warmup", "target": key, "design": design,
                                   "server_elapsed_ms": elapsed, "first_page_sha256": digest})
        measured_rounds = [(1, ("A", "B")), (2, ("B", "A"))]
        for round_index, order in measured_rounds:
            for key, (product_id, _) in TARGETS.items():
                for design in order:
                    elapsed, digest = sample_query("reviews_a" if design == "A" else "reviews_b", product_id)
                    raw_samples.append({"round": round_index, "target": key, "design": design,
                                        "server_elapsed_ms": elapsed, "first_page_sha256": digest})
        target_values = {}
        extra_targets = set()
        for key, (product_id, expected) in TARGETS.items():
            a_full = full_result("reviews_a", product_id)
            b_full = full_result("reviews_b", product_id)
            old_hash_matches = a_full[1] == OLD_FULL_HASHES[key]
            if a_full != b_full or a_full[0] != expected:
                raise RuntimeError(f"full ordered rows mismatch for {key}: A={a_full}, B={b_full}")
            page_hashes = {item["first_page_sha256"] for item in raw_samples if item["target"] == key}
            if len(page_hashes) != 1:
                raise RuntimeError(f"first-page hash mismatch across samples for {key}")
            values = {design: [item["server_elapsed_ms"] for item in raw_samples
                               if item.get("round") and item["target"] == key and item["design"] == design]
                      for design in ("A", "B")}
            if any(max(samples) / min(samples) > 1.25 for samples in values.values()):
                extra_targets.add(key)
            target_values[key] = {"product_id": product_id, "rows": a_full[0],
                                  "full_ordered_sha256": a_full[1], "matches_previous_logical_fixture_hash": old_hash_matches,
                                  "first_page_hashes_equal": True, "three_round_required": key in extra_targets}
            for design, table in (("A", "reviews_a"), ("B", "reviews_b")):
                (artifact / f"explain-{key}-{design}.txt").write_text(
                    lab.sql("EXPLAIN " + query(table, product_id, 21), SCHEMA) + "\n")
                (artifact / f"explain-analyze-{key}-{design}.txt").write_text(
                    lab.sql("EXPLAIN ANALYZE " + query(table, product_id, 21), SCHEMA) + "\n")
        for key in sorted(extra_targets):
            product_id, _ = TARGETS[key]
            order = ("A", "B") if key != "medium_1000" else ("B", "A")
            for design in order:
                elapsed, digest = sample_query("reviews_a" if design == "A" else "reviews_b", product_id)
                raw_samples.append({"round": 3, "target": key, "design": design,
                                    "server_elapsed_ms": elapsed, "first_page_sha256": digest})
        # Recheck hashes after optional third samples.
        for key in TARGETS:
            if len({item["first_page_sha256"] for item in raw_samples if item["target"] == key}) != 1:
                raise RuntimeError(f"first-page hash mismatch after adaptive sample for {key}")
            values = {design: [item["server_elapsed_ms"] for item in raw_samples
                               if item.get("round") and item["target"] == key and item["design"] == design]
                      for design in ("A", "B")}
            target_values[key]["samples"] = values
            target_values[key]["medians_ms"] = {design: statistics.median(samples) for design, samples in values.items()}
            target_values[key]["three_round_spread_still_over_25pct"] = any(
                max(samples) / min(samples) > 1.25 for samples in values.values()) if len(values["A"]) == 3 else None
        source_after = source_snapshot()
        report = record_artifact(artifact, source_before, reused, setup, raw_samples, target_values, source_after)
        report["fixture"] = fixture
        report["fixture_retained"] = True
        report["adaptive_third_round_targets"] = sorted(extra_targets)
        report["seed_method"] = "sequential primary-key insertion using inverse modular permutation; B copied from A ordered by id"
        (artifact / "raw-and-summary.json").write_text(json.dumps(report, indent=2) + "\n")
        print(f"Artifacts: {artifact.relative_to(ROOT)}")
    except Exception as error:
        report = {"error": f"{type(error).__name__}: {error}", "schema": SCHEMA,
                  "fixture_retained": schema_exists(), "source_before": source_before,
                  "setup_timings_ms": setup, "raw_samples": raw_samples, "targets": results}
        try:
            report["source_after"] = source_snapshot()
            report["source_unchanged"] = report["source_after"] == source_before
            report["review_metadata"] = metadata() if schema_exists() else None
        except Exception as verify_error:
            report["source_verify_error"] = f"{type(verify_error).__name__}: {verify_error}"
        (artifact / "raw-and-summary.json").write_text(json.dumps(report, indent=2) + "\n")
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("run", "status"), nargs="?", default="run")
    args = parser.parse_args()
    try:
        if args.command == "status":
            status()
        else:
            run()
    except Exception as error:
        print(f"benchmark failed: {error}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
