#!/usr/bin/env python3
"""Persistent SQL-only customer campaign-entry history index comparison."""
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
SCHEMA = "bench_entries"
TABLE_1M = "campaign_activity_entries"
META_1M = "entry_history_fixture_meta"
TABLE = TABLE_1M
META = META_1M
OWNER_1M = "axon-campaign-entry-history-index-benchmark"
OWNER = OWNER_1M
VERSION = "1"
ROWS = 1_000_000
USERS = 100_000
ACTIVITIES = 100
DATE_DIVISOR = 10
SEED = "user=1+mod(n,100000);activity=1+floor(n/10000);requested=permutation(n*7919 mod 1000000)/10"
ANCHOR = "2026-10-14 12:00:00"
SCALE = "1m"

def configure_scale(scale):
    global TABLE, META, OWNER, ROWS, USERS, ACTIVITIES, DATE_DIVISOR, SEED, SCALE
    SCALE = scale
    if scale == "1m":
        TABLE, META, OWNER = TABLE_1M, META_1M, OWNER_1M
        ROWS, USERS, ACTIVITIES, DATE_DIVISOR = 1_000_000, 100_000, 100, 10
        SEED = "user=1+mod(n,100000);activity=1+floor(n/10000);requested=permutation(n*7919 mod 1000000)/10"
    elif scale == "3m":
        TABLE, META, OWNER = "campaign_activity_entries_3m", "entry_history_fixture_meta_3m", "axon-campaign-entry-history-3m-benchmark"
        ROWS, USERS, ACTIVITIES, DATE_DIVISOR = 3_000_000, 300_000, 300, 30
        SEED = "user=1+mod(n,300000);activity=1+floor(n/10000);requested=permutation(n*7919 mod 3000000)/30"
    else:
        raise ValueError(f"unsupported scale: {scale}")

INDEXES = {
    "A0": "PRIMARY\t1\tid\t0",
    "A1": "PRIMARY\t1\tid\t0\nuk_entry_activity_user\t1\tcampaign_activity_id\t0\nuk_entry_activity_user\t2\tuser_id\t0",
    "B": "idx_entry_user\t1\tuser_id\t1\nPRIMARY\t1\tid\t0\nuk_entry_activity_user\t1\tcampaign_activity_id\t0\nuk_entry_activity_user\t2\tuser_id\t0",
    "C": "idx_entry_user_requested\t1\tuser_id\t1\nidx_entry_user_requested\t2\trequested_at\t1\nPRIMARY\t1\tid\t0\nuk_entry_activity_user\t1\tcampaign_activity_id\t0\nuk_entry_activity_user\t2\tuser_id\t0",
}
TARGETS = {"user_1001": 1001, "user_50001": 50001, "user_99999": 99999}


def q(statement, db=SCHEMA):
    return lab.sql(statement, db)


def schema_exists():
    return q(f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{SCHEMA}'", "mysql") == "1"


def index_signature():
    return q("SELECT index_name,seq_in_index,column_name,non_unique FROM information_schema.statistics "
             f"WHERE table_schema='{SCHEMA}' AND table_name='{TABLE}' ORDER BY index_name,seq_in_index", "mysql")


def metadata():
    return q(f"SELECT owner,version,logical_seed,row_count,ready FROM {META} WHERE id=1")


def exact_rows():
    count = int(q(f"SELECT COUNT(*) FROM {TABLE}"))
    if count != ROWS:
        raise RuntimeError(f"entry fixture row count mismatch: {count}")
    shape = q(f"SELECT COUNT(DISTINCT user_id),MIN(user_rows),MAX(user_rows) FROM "
              f"(SELECT user_id,COUNT(*) user_rows FROM {TABLE} GROUP BY user_id) x")
    if shape != f"{USERS}\t10\t10":
        raise RuntimeError(f"entry fixture user distribution mismatch: {shape!r}")
    activities = q(f"SELECT COUNT(DISTINCT campaign_activity_id),MIN(activity_rows),MAX(activity_rows) FROM "
                   f"(SELECT campaign_activity_id,COUNT(*) activity_rows FROM {TABLE} GROUP BY campaign_activity_id) x")
    if activities != f"{ACTIVITIES}\t10000\t10000":
        raise RuntimeError(f"entry fixture activity distribution mismatch: {activities!r}")
    return count


def verify_fixture(require_ready=True):
    if not schema_exists():
        raise RuntimeError("bench_entries does not exist; run is the only fixture-creation command")
    meta = metadata()
    expected = f"{OWNER}\t{VERSION}\t{SEED}\t{ROWS}\t1"
    if meta != expected:
        raise RuntimeError(f"fixture owner/version/seed/ready mismatch: {meta!r}")
    cols = q("SELECT column_name,column_type,is_nullable,extra FROM information_schema.columns "
             f"WHERE table_schema='{SCHEMA}' AND table_name='{TABLE}' ORDER BY ordinal_position", "mysql")
    expected_cols = ("id\tbigint\tNO\tauto_increment\n"
                     "campaign_activity_id\tbigint\tNO\t\nproduct_id\tbigint\tYES\t\n"
                     "status\tvarchar(50)\tNO\t\nuser_id\tbigint\tNO\t\n"
                     "requested_at\tdatetime(6)\tNO\t\nprocessed_at\tdatetime(6)\tYES\t\n"
                     "additional_data\tvarchar(255)\tYES\t\ncreated_at\tdatetime(6)\tNO\t\n"
                     "updated_at\tdatetime(6)\tNO")
    if cols != expected_cols:
        raise RuntimeError(f"fixture schema mismatch: {cols!r}")
    sig = index_signature()
    if sig not in INDEXES.values():
        raise RuntimeError(f"unrecognized entry index signature: {sig!r}")
    return {"metadata": meta, "rows": exact_rows(), "indexes": sig, "columns": cols}


def source_snapshot():
    lab.verify_lab()
    result = {}
    for db, table, expected in (("bench_candidates", "abandoned_cart_coupon_candidates", 5_000_000),
                                 ("bench_purchases", "purchases", 1_000_000)):
        meta = lab.sql("SELECT version,row_count,anchor_at,ready FROM _fixture", db)
        count = int(lab.sql(f"SELECT COUNT(*) FROM {table}", db))
        if count != expected:
            raise RuntimeError(f"source fixture count changed/unexpected {db}.{table}: {count}")
        indexes = lab.sql("SELECT index_name,seq_in_index,column_name,non_unique FROM information_schema.statistics "
                           f"WHERE table_schema='{db}' AND table_name='{table}' ORDER BY index_name,seq_in_index")
        result[db] = {"metadata": meta, "count": count, "indexes": indexes}
    return result


def review_snapshot():
    # Read-only consistency check of the earlier persistent review fixtures in the shared lab.
    result = {}
    for table in ("reviews_a", "reviews_b", "review_attributes"):
        exists = lab.sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='bench_reviews' "
                         f"AND table_name='{table}'") == "1"
        if exists:
            count = int(lab.sql(f"SELECT COUNT(*) FROM {table}", "bench_reviews"))
            indexes = lab.sql("SELECT index_name,seq_in_index,column_name,non_unique FROM information_schema.statistics "
                              f"WHERE table_schema='bench_reviews' AND table_name='{table}' ORDER BY index_name,seq_in_index")
            result[table] = {"count": count, "indexes": indexes}
    for table, expected in (("reviews_a", 1_000_000), ("reviews_b", 1_000_000), ("review_attributes", 1_000_000)):
        if table in result and result[table]["count"] != expected:
            raise RuntimeError(f"prior review fixture changed/unexpected: {table} {result[table]['count']}")
    return result


def meta_table_exists():
    return q(f"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='{SCHEMA}' AND table_name='{META}'", "mysql") == "1"


def data_table_exists():
    return q(f"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='{SCHEMA}' AND table_name='{TABLE}'", "mysql") == "1"


def record_setup(artifact, name, started, error=None):
    path = artifact / "setup-events.json"
    events = json.loads(path.read_text()) if path.exists() else []
    events.append({"event": name, "elapsed_seconds_wall_client_included": round(datetime.now(timezone.utc).timestamp() - started, 6),
                   "status": "failed" if error else "completed", "error": str(error) if error else None})
    path.write_text(json.dumps(events, indent=2) + "\n")


def setup_sql(artifact, name, statement, db=SCHEMA):
    started = datetime.now(timezone.utc).timestamp()
    error = None
    try:
        return lab.sql(statement, db)
    except Exception as exc:
        error = exc
        raise
    finally:
        record_setup(artifact, name, started, error)


def create_fixture(artifact):
    schema_present = schema_exists()
    meta_present = meta_table_exists() if schema_present else False
    data_present = data_table_exists() if schema_present else False
    if meta_present != data_present:
        if not meta_present and not data_present:
            pass
        else:
            raise RuntimeError(f"partial scale fixture collision: metadata={meta_present}, table={data_present}")
    if meta_present and data_present:
        meta = metadata()
        ready_meta = f"{OWNER}\t{VERSION}\t{SEED}\t{ROWS}\t1"
        if meta == ready_meta:
            return True, verify_fixture()
        raise RuntimeError(f"existing {SCALE} fixture is not ready/exact; refusing drop or reseed: {meta!r}")
    if not schema_present:
        setup_sql(artifact, "create_schema", f"CREATE DATABASE {SCHEMA}", "mysql")
    meta_ddl = (f"CREATE TABLE {META} (id INT PRIMARY KEY, owner VARCHAR(80) NOT NULL, version VARCHAR(20) NOT NULL, "
                "logical_seed VARCHAR(255) NOT NULL, row_count BIGINT NOT NULL, ready BOOLEAN NOT NULL, created_at DATETIME(6) NOT NULL)")
    setup_sql(artifact, "create_metadata_table", meta_ddl)
    setup_sql(artifact, "insert_metadata_unready", f"INSERT INTO {META} VALUES (1,'{OWNER}','{VERSION}','{SEED}',{ROWS},false,'{ANCHOR}')")
    ddl = f"""CREATE TABLE {TABLE} (
 id BIGINT NOT NULL AUTO_INCREMENT,
 campaign_activity_id BIGINT NOT NULL,
 product_id BIGINT NULL,
 status VARCHAR(50) NOT NULL,
 user_id BIGINT NOT NULL,
 requested_at DATETIME(6) NOT NULL,
 processed_at DATETIME(6) NULL,
 additional_data VARCHAR(255) NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY(id)
) ENGINE=InnoDB"""
    setup_sql(artifact, "create_pk_only_table", ddl)
    number_source = ("SELECT c.n*1000000+a.n*1000+b.n AS n FROM bench_candidates._seq c "
                     "CROSS JOIN bench_candidates._seq a CROSS JOIN bench_candidates._seq b WHERE c.n<3")
    if ROWS == 1_000_000:
        number_source = "SELECT a.n*1000+b.n AS n FROM bench_candidates._seq a CROSS JOIN bench_candidates._seq b"
    seed_sql = f"""INSERT INTO {TABLE}(id,campaign_activity_id,product_id,status,user_id,requested_at,processed_at,additional_data,created_at,updated_at)
SELECT n+1, FLOOR(n/10000)+1,
       IF(MOD(n*37,7)=0,NULL,1+MOD(n*37,10000)),
       CASE MOD(n,100) WHEN 0 THEN 'PENDING' WHEN 1 THEN 'REJECTED' WHEN 2 THEN 'DUPLICATED' ELSE 'APPROVED' END,
       1+MOD(n,{USERS}),
       DATE_SUB('{ANCHOR}', INTERVAL FLOOR(MOD(n*7919,{ROWS})/{DATE_DIVISOR}) SECOND),
       IF(MOD(n,100)=0,NULL,DATE_ADD(DATE_SUB('{ANCHOR}', INTERVAL FLOOR(MOD(n*7919,{ROWS})/{DATE_DIVISOR}) SECOND),INTERVAL MOD(n,3600) SECOND)),
       IF(MOD(n,13)=0,NULL,CONCAT('synthetic-entry-',n,'-campaign-history')),
       DATE_SUB('{ANCHOR}', INTERVAL FLOOR(MOD(n*7919,{ROWS})/{DATE_DIVISOR}) SECOND),
       DATE_SUB('{ANCHOR}', INTERVAL FLOOR(MOD(n*7919,{ROWS})/{DATE_DIVISOR}) SECOND)
FROM ({number_source}) seq
ORDER BY n"""
    (artifact / "schema.sql").write_text(f"CREATE DATABASE {SCHEMA};\n{ddl};\n")
    (artifact / "seed.sql").write_text(seed_sql + ";\n")
    print(f"seed: inserting {ROWS:,} rows ordered by explicit id", flush=True)
    setup_sql(artifact, "ordered_pk_seed", seed_sql)
    rows = verify_fixture_without_ready()
    setup_sql(artifact, "mark_ready", f"UPDATE {META} SET ready=true WHERE id=1")
    return False, {"seeded_rows": rows, "setup_events_file": "setup-events.json"}

def verify_fixture_without_ready():
    meta = q(f"SELECT owner,version,logical_seed,row_count,ready FROM {META} WHERE id=1")
    if meta != f"{OWNER}\t{VERSION}\t{SEED}\t{ROWS}\t0":
        raise RuntimeError(f"unready fixture metadata mismatch: {meta!r}")
    rows = int(q(f"SELECT COUNT(*) FROM {TABLE}"))
    if rows != ROWS:
        raise RuntimeError(f"expected {ROWS} seeded rows, got {rows}")
    dist = q(f"SELECT COUNT(DISTINCT user_id),MIN(c),MAX(c) FROM (SELECT user_id,COUNT(*) c FROM {TABLE} GROUP BY user_id) x")
    if dist != f"{USERS}\t10\t10":
        raise RuntimeError(f"seeded user distribution mismatch: {dist!r}")
    return rows


def query_for(user_id):
    return (f"SELECT CONCAT_WS(CHAR(31),id,campaign_activity_id,IFNULL(product_id,'NULL'),status,user_id,"
            f"DATE_FORMAT(requested_at,'%Y-%m-%d %H:%i:%s.%f'),IFNULL(DATE_FORMAT(processed_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'),"
            f"IFNULL(additional_data,'NULL'),DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f')) "
            f"FROM {TABLE} WHERE user_id={user_id} ORDER BY requested_at DESC,id DESC LIMIT 21")


def digest_rows(rows):
    return hashlib.sha256("\n".join(rows).encode()).hexdigest()


def set_arm(arm, artifact=None):
    sig = index_signature()
    if sig not in INDEXES.values():
        raise RuntimeError(f"refusing index switch from unknown signature: {sig!r}")
    if sig == INDEXES[arm]:
        return
    index_names = {line.split("\t", 1)[0] for line in sig.splitlines()}
    def ddl(label, statement):
        if artifact is None:
            return q(statement)
        return setup_sql(artifact, label, statement)
    for idx in ("idx_entry_user", "idx_entry_user_requested"):
        if idx in index_names:
            ddl(f"drop_{idx}", f"DROP INDEX {idx} ON {TABLE}")
    has_unique = "uk_entry_activity_user" in index_names
    if arm == "A0" and has_unique:
        ddl("drop_business_unique", f"DROP INDEX uk_entry_activity_user ON {TABLE}")
    elif arm != "A0" and not has_unique:
        ddl("create_business_unique", f"CREATE UNIQUE INDEX uk_entry_activity_user ON {TABLE}(campaign_activity_id,user_id)")
    if arm == "B":
        ddl("create_user_index", f"CREATE INDEX idx_entry_user ON {TABLE}(user_id)")
    elif arm == "C":
        ddl("create_user_requested_index", f"CREATE INDEX idx_entry_user_requested ON {TABLE}(user_id,requested_at)")
    actual = index_signature()
    if actual != INDEXES[arm]:
        raise RuntimeError(f"index switch failed: expected {arm}, got {actual!r}")
    ddl("analyze_table", f"ANALYZE TABLE {TABLE}")


def entry_1m_snapshot():
    table_exists = q(f"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='{SCHEMA}' AND table_name='{TABLE_1M}'", "mysql") == "1"
    meta_exists = q(f"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='{SCHEMA}' AND table_name='{META_1M}'", "mysql") == "1"
    if not table_exists or not meta_exists:
        raise RuntimeError("original 1M entry fixture missing")
    meta = q(f"SELECT owner,version,logical_seed,row_count,ready FROM {META_1M} WHERE id=1")
    expected = ("axon-campaign-entry-history-index-benchmark\t1\t"
                "user=1+mod(n,100000);activity=1+floor(n/10000);requested=permutation(n*7919 mod 1000000)/10\t1000000\t1")
    if meta != expected:
        raise RuntimeError(f"original 1M entry metadata changed: {meta!r}")
    count = int(q(f"SELECT COUNT(*) FROM {TABLE_1M}"))
    sig = q("SELECT index_name,seq_in_index,column_name,non_unique FROM information_schema.statistics "
            f"WHERE table_schema='{SCHEMA}' AND table_name='{TABLE_1M}' ORDER BY index_name,seq_in_index", "mysql")
    if count != 1_000_000 or sig != INDEXES["A1"]:
        raise RuntimeError(f"original 1M entry fixture changed: count={count}, indexes={sig!r}")
    return {"metadata": meta, "count": count, "indexes": sig}


def run(artifact):
    source_before = source_snapshot()
    reviews_before = review_snapshot()
    original_entries_before = entry_1m_snapshot() if SCALE == "3m" else None
    reused, setup = create_fixture(artifact)
    base = verify_fixture()
    ids = ",".join(map(str, TARGETS.values()))
    match_count = q(f"SELECT user_id,COUNT(*) FROM {TABLE} WHERE user_id IN ({ids}) GROUP BY user_id ORDER BY user_id")
    if match_count != "1001\t10\n50001\t10\n99999\t10":
        raise RuntimeError(f"sample user count mismatch: {match_count!r}")
    expected = {}
    plans = {}
    warmups = []
    raw = []
    arm_names = ("A0", "A1", "B", "C")
    round_arms = [("A0", "A1", "B", "C"), ("C", "B", "A1", "A0")]
    for arm in arm_names:
        set_arm(arm, artifact)
        arm_hashes = {}
        for name, uid in TARGETS.items():
            rows = q(query_for(uid)).splitlines()
            if len(rows) != 10:
                raise RuntimeError(f"{name}: expected 10 rows, got {len(rows)}")
            arm_hashes[name] = digest_rows(rows)
            if name in expected and expected[name] != arm_hashes[name]:
                raise RuntimeError(f"warmup result differs across index arms for {name}")
            expected[name] = arm_hashes[name]
        warmups.append({"arm": arm, "target_hashes": arm_hashes})
    def sample(arm, round_no, names):
        set_arm(arm, artifact)
        for name in names:
            uid = TARGETS[name]
            query = query_for(uid)
            output = q("SET @entry_started=SYSDATE(6);" + query + ";SELECT CONCAT('LAB_ELAPSED_MS\\t',TIMESTAMPDIFF(MICROSECOND,@entry_started,SYSDATE(6))/1000)")
            lines = output.splitlines()
            elapsed = float(lines[-1].split("\t")[1])
            rows = lines[:-1]
            digest = digest_rows(rows)
            if len(rows) != 10 or digest != expected[name]:
                raise RuntimeError(f"result invariant failed for {arm}/{name}: rows={len(rows)}, hash={digest}")
            raw.append({"round": round_no, "arm": arm, "target": name, "user_id": uid,
                        "rows": len(rows), "full_ordered_sha256": digest, "server_elapsed_ms": elapsed})
        if arm not in plans:
            uid = TARGETS["user_50001"]
            plans[arm] = {"explain": q("EXPLAIN FORMAT=JSON " + query_for(uid)),
                          "explain_analyze": q("EXPLAIN ANALYZE " + query_for(uid))}
    for round_no, arms in enumerate(round_arms, 1):
        for arm in arms:
            sample(arm, round_no, list(TARGETS))
    # Add at most one third sample per target/arm only when its two samples vary by >25%.
    third_samples = []
    for name in TARGETS:
        for arm in arm_names:
            vals = [x["server_elapsed_ms"] for x in raw if x["target"] == name and x["arm"] == arm and x["round"] <= 2]
            median = statistics.median(vals)
            spread = (max(vals) - min(vals)) / median if median else 0
            if spread > 0.25:
                sample(arm, 3, [name])
                third_samples.append({"target": name, "arm": arm, "two_sample_relative_spread": spread})
    # Restore and verify canonical application base A1; retain the fixture.
    set_arm("A1", artifact)
    final_fixture = verify_fixture()
    source_after = source_snapshot()
    reviews_after = review_snapshot()
    original_entries_after = entry_1m_snapshot() if SCALE == "3m" else None
    if source_before != source_after or reviews_before != reviews_after or original_entries_before != original_entries_after:
        raise RuntimeError("source or earlier review fixture changed during entry experiment")
    result = {"scope": "SQL-only local synthetic fixture; no API/JPA/HTTP or campaign detail join",
              "scale": SCALE, "owner": OWNER, "version": VERSION, "logical_seed": SEED, "rows": ROWS,
              "users": USERS, "activities": ACTIVITIES,
              "fixture_reused": reused, "setup": setup, "sample_users": TARGETS,
              "sample_user_record_counts": match_count, "warmup": warmups,
              "measurement_order": [["A0", "A1", "B", "C"], ["C", "B", "A1", "A0"]],
              "warmup_note": "One recorded warmup per arm; failed-attempt warmups are excluded from this run's raw measurements.",
              "adaptive_third_samples": third_samples, "measurements": raw, "medians_ms_by_target_arm": {}, "plans": plans,
              "all_ordered_result_hashes_equal": all(x["full_ordered_sha256"] == expected[x["target"]] for x in raw),
              "same_result_hash_by_target": expected, "final_indexes": final_fixture["indexes"], "canonical_restored": final_fixture["indexes"] == INDEXES["A1"],
              "final_fixture": final_fixture, "source_before": source_before, "source_after": source_after,
              "prior_review_fixtures_before": reviews_before, "prior_review_fixtures_after": reviews_after,
              "original_1m_entries_before": original_entries_before, "original_1m_entries_after": original_entries_after,
              "mysql_version": lab.sql("SELECT VERSION()"),
              "clock": "server-side SQL elapsed time; excludes Docker/mysql process startup; DDL and seed excluded",
              "limitations": ["A0 PK-only is diagnostic control only; it does not propose removing the application uniqueness constraint. A1 is the canonical application baseline.",
                             f"Synthetic {USERS:,}-user x 10-entry and {ACTIVITIES}-activity x 10k-entry distribution, not an operational distribution estimate.",
                             "Synthetic activity/user IDs have no foreign keys; this checks SQL/index behavior only.",
                             "Status, products, info, and timestamps are deterministic synthetic values; no status filter or activity-detail JOIN.",
                             "Index DDL and ANALYZE between arms can affect cache/statistics; this is not an isolated production read benchmark."]}
    for name in TARGETS:
        for arm in ("A0", "A1", "B", "C"):
            values = [x["server_elapsed_ms"] for x in raw if x["target"] == name and x["arm"] == arm]
            result["medians_ms_by_target_arm"].setdefault(name, {})[arm] = statistics.median(values)
    (artifact / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
    for arm, plan in plans.items():
        (artifact / f"explain-{arm}.json").write_text(plan["explain"] + "\n")
        (artifact / f"explain-analyze-{arm}.txt").write_text(plan["explain_analyze"] + "\n")
    return result


def status():
    lab.verify_lab()
    has_schema = schema_exists()
    has_meta = meta_table_exists() if has_schema else False
    has_data = data_table_exists() if has_schema else False
    if has_meta != has_data:
        raise RuntimeError(f"partial {SCALE} fixture collision: metadata={has_meta}, table={has_data}")
    fixture = verify_fixture() if has_meta and has_data else None
    print(json.dumps({"sql_lab": "axon-local-sql-lab", "scale": SCALE, "schema_exists": has_schema,
                      "fixture_exists": has_meta and has_data, "fixture": fixture}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    status_cmd = sub.add_parser("status", help="read-only fixture validation; performs no DDL, seeding, or samples")
    status_cmd.add_argument("--scale", choices=("1m", "3m"), default="1m")
    run_cmd = sub.add_parser("run", help="create once if absent, then benchmark/reuse the persistent fixture")
    run_cmd.add_argument("--scale", choices=("1m", "3m"), default="1m")
    args = parser.parse_args()
    configure_scale(args.scale)
    lab.verify_lab()
    if args.command == "status":
        status()
        return
    artifact = ROOT / "build/benchmark" / f"campaign-entry-history-{SCALE}" / datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    artifact.mkdir(parents=True, exist_ok=False)
    shutil.copy2(Path(__file__), artifact / Path(__file__).name)
    (artifact / "query.sql").write_text(f"SELECT id,campaign_activity_id,product_id,status,user_id,requested_at,processed_at,additional_data,created_at,updated_at FROM {TABLE} WHERE user_id=? ORDER BY requested_at DESC,id DESC LIMIT 21;\nA0=PK only (diagnostic), A1=PK+UNIQUE(activity,user), B=A1+user_id, C=A1+(user_id,requested_at).\n\n")
    try:
        report = run(artifact)
    finally:
        if data_table_exists():
            set_arm("A1", artifact)
    print(json.dumps({"artifact": str(artifact.relative_to(ROOT)), "scale": SCALE, "fixture_reused": report["fixture_reused"],
                      "medians_ms_by_target_arm": report["medians_ms_by_target_arm"],
                      "final_indexes": report["final_indexes"]}, indent=2), flush=True)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, Exception) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise
