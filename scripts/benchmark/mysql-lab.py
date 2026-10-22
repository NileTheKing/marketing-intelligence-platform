#!/usr/bin/env python3
"""Persistent, local-only SQL fixtures. Never connects to application databases."""

import argparse
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
import sys
from datetime import datetime


ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ["docker", "compose", "--project-directory", str(ROOT), "-p", "axon-benchmark", "-f", str(ROOT / "infrastructure/compose/compose.benchmark.yml")]
DATABASES = {"candidates": "bench_candidates", "purchases": "bench_purchases"}
VERSION = "1"
ANCHOR = "2026-10-14 12:00:00"


def sql(statement, database="mysql"):
    result = subprocess.run(
        COMPOSE + ["exec", "-T", "-e", "MYSQL_PWD=benchmark-local-only", "mysql",
                   "mysql", "-uroot", "--batch", "--raw", "--skip-column-names", database],
        input=statement, text=True, capture_output=True, check=True,
    )
    return result.stdout.strip()


def verify_lab():
    # Both Compose identity and port/volume are fixed; never accept an arbitrary URL.
    if sql("SELECT lab_id FROM benchmark_lab.identity WHERE id=1") != "axon-local-sql-lab":
        raise RuntimeError("Not the dedicated SQL lab")


def numbers_schema():
    return """
CREATE TABLE _digits(n INT PRIMARY KEY);
INSERT INTO _digits VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
CREATE TABLE _seq(n INT PRIMARY KEY);
INSERT INTO _seq SELECT a.n*100+b.n*10+c.n
FROM _digits a CROSS JOIN _digits b CROSS JOIN _digits c;
CREATE TABLE _fixture(version VARCHAR(20), row_count BIGINT, anchor_at DATETIME, ready BOOLEAN);
"""


def seed(dataset, rows, reset):
    verify_lab()
    database = DATABASES[dataset]
    table = "abandoned_cart_coupon_candidates" if dataset == "candidates" else "purchases"
    exists = sql(f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{database}'") == "1"
    if exists and not reset:
        metadata = sql(f"SELECT version,row_count,ready FROM {database}._fixture")
        if metadata == f"{VERSION}\t{rows}\t1" and sql(f"SELECT COUNT(*) FROM {table}", database) == str(rows):
            print(f"{dataset}: reuse {rows:,} existing rows", flush=True)
            return
        raise RuntimeError(f"{dataset}: incomplete or different fixture; use seed {dataset} --reset explicitly")
    if reset:
        sql(f"DROP DATABASE IF EXISTS {database}")
    sql(f"CREATE DATABASE {database}")
    sql(numbers_schema() + f"INSERT INTO _fixture VALUES ('{VERSION}',{rows},'{ANCHOR}',false)", database)
    schema = CANDIDATE_SCHEMA if dataset == "candidates" else PURCHASE_SCHEMA
    sql(schema, database)
    print(f"{dataset}: seeding {rows:,} rows once", flush=True)
    for offset in range(0, rows, 1_000_000):
        limit = min(rows - offset, 1_000_000)
        source = f"(SELECT {offset}+a.n*1000+b.n AS n FROM _seq a CROSS JOIN _seq b WHERE a.n*1000+b.n<{limit}) numbers"
        statement = CANDIDATE_INSERT if dataset == "candidates" else PURCHASE_INSERT
        sql(statement.format(source=source), database)
        print(f"{dataset}: {offset + limit:,}/{rows:,}", flush=True)
    actual = int(sql(f"SELECT COUNT(*) FROM {table}", database))
    if actual != rows:
        raise RuntimeError(f"Expected {rows} rows, got {actual}")
    sql(f"ANALYZE TABLE {table}; UPDATE _fixture SET ready=true", database)
    print(f"{dataset}: ready, data survives stop/start", flush=True)


CANDIDATE_SCHEMA = """
CREATE TABLE marketing_actions (
 id BIGINT PRIMARY KEY, action_type VARCHAR(20) NOT NULL, reference_id BIGINT NOT NULL,
 is_active BOOLEAN NOT NULL, marketing_rule_id BIGINT NULL, created_at DATETIME, updated_at DATETIME);
CREATE TABLE abandoned_cart_coupon_campaigns (
 id BIGINT PRIMARY KEY, marketing_action_id BIGINT NOT NULL UNIQUE, delay_minutes INT NOT NULL,
 is_active BOOLEAN NOT NULL, FOREIGN KEY(marketing_action_id) REFERENCES marketing_actions(id));
INSERT INTO marketing_actions SELECT n+1,'COUPON',1001+n,true,NULL,'2026-10-13','2026-10-13' FROM _seq WHERE n<20;
INSERT INTO abandoned_cart_coupon_campaigns SELECT n+1,n+1,30,true FROM _seq WHERE n<20;
CREATE TABLE abandoned_cart_coupon_candidates (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, campaign_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 product_id BIGINT NOT NULL, last_cart_activity_at DATETIME NOT NULL, due_at DATETIME NOT NULL,
 status VARCHAR(20) NOT NULL, claimed_at DATETIME NULL, marketing_action_execution_id BIGINT NULL,
 UNIQUE KEY uk_candidate(campaign_id,user_id,product_id),
 KEY idx_campaign_due(campaign_id,status,due_at,id),
 FOREIGN KEY(campaign_id) REFERENCES abandoned_cart_coupon_campaigns(id));
"""

CANDIDATE_INSERT = """
INSERT INTO abandoned_cart_coupon_candidates
(id,campaign_id,user_id,product_id,last_cart_activity_at,due_at,status)
SELECT n+1,1+MOD(FLOOR(n/100),20),n+1,1+MOD(n,1000),
 DATE_SUB(due_at,INTERVAL 30 MINUTE),due_at,status FROM (
 SELECT n,
 CASE WHEN MOD(n,100)=0 THEN DATE_SUB('2026-10-14 12:00:00',INTERVAL (1+MOD(n,1440)) MINUTE)
      WHEN MOD(n,100)<5 THEN DATE_ADD('2026-10-14 12:00:00',INTERVAL (1+MOD(n,43200)) MINUTE)
      WHEN MOD(n,100)<15 THEN DATE_ADD('2026-09-14 12:00:00',INTERVAL MOD(n,43200) MINUTE)
      ELSE DATE_ADD('2025-10-14 12:00:00',INTERVAL MOD(n,525600) MINUTE) END due_at,
 CASE WHEN MOD(n,100)<5 THEN 'SCHEDULED' WHEN MOD(n,100)<15 THEN 'CANCELLED' ELSE 'DISPATCHED' END status
 FROM {source}) prepared;
"""

PURCHASE_SCHEMA = """
CREATE TABLE purchases (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, product_id BIGINT NOT NULL,
 campaign_activity_id BIGINT NULL, purchase_type VARCHAR(20) NOT NULL,
 price DECIMAL(10,2) NOT NULL, quantity INT NOT NULL, purchase_at DATETIME NOT NULL,
 status VARCHAR(20) NOT NULL, cancelled_at DATETIME NULL, cancellation_reason VARCHAR(500) NULL,
 user_coupon_id BIGINT NULL,
 UNIQUE KEY uk_campaign_purchase_user(campaign_activity_id,user_id),
 UNIQUE KEY uk_purchase_user_coupon(user_coupon_id),
 KEY idx_purchase_activity_lookup(campaign_activity_id,purchase_type,purchase_at),
 KEY idx_purchase_activity_status_period(campaign_activity_id,status,purchase_at),
 KEY idx_purchase_user_history(user_id,purchase_at));
"""

PURCHASE_INSERT = """
INSERT INTO purchases(id,user_id,product_id,campaign_activity_id,purchase_type,price,quantity,purchase_at,status,cancelled_at,cancellation_reason)
SELECT n+1,CASE WHEN n<8000 THEN MOD(n,800)+1 ELSE 100000+MOD(n,100000) END,
 1+MOD(n,1000),CASE WHEN n<800 THEN 1 ELSE NULL END,
 CASE WHEN n<800 THEN 'CAMPAIGNACTIVITY' ELSE 'SHOP' END,10000+MOD(n,10000),1,
 CASE WHEN n<8000 THEN DATE_ADD('2026-10-01',INTERVAL MOD(n,168) HOUR)
      ELSE DATE_ADD('2025-10-01',INTERVAL MOD(n,8760) HOUR) END,
 CASE WHEN n<8000 OR MOD(n,20)<>0 THEN 'CONFIRMED' ELSE 'CANCELLED' END,
 CASE WHEN n>=8000 AND MOD(n,20)=0 THEN DATE_ADD('2025-10-02',INTERVAL MOD(n,8760) HOUR) ELSE NULL END,
 CASE WHEN n>=8000 AND MOD(n,20)=0 THEN 'Synthetic cancellation' ELSE NULL END
FROM {source};
"""


JOIN = """
SELECT c.id,c.campaign_id,c.user_id,c.product_id,c.last_cart_activity_at,c.due_at,c.status,
 c.claimed_at,c.marketing_action_execution_id,ac.id,ac.marketing_action_id,ac.delay_minutes,ac.is_active,
 ma.id,ma.action_type,ma.reference_id,ma.is_active,ma.marketing_rule_id,ma.created_at,ma.updated_at
FROM abandoned_cart_coupon_candidates c
JOIN abandoned_cart_coupon_campaigns ac ON ac.id=c.campaign_id
JOIN marketing_actions ma ON ma.id=ac.marketing_action_id
"""
FILTER = f"WHERE c.status='SCHEDULED' AND c.due_at<='{ANCHOR}' ORDER BY c.due_at,c.id LIMIT 500"


def compare(repeat):
    verify_lab()
    database = DATABASES["candidates"]
    if sql("SELECT ready FROM _fixture", database) != "1":
        raise RuntimeError("Seed candidates first")
    rows = int(sql("SELECT row_count FROM _fixture", database))
    due = int(sql(f"SELECT COUNT(*) FROM abandoned_cart_coupon_candidates "
                  f"WHERE status='SCHEDULED' AND due_at<='{ANCHOR}'", database))
    if due != rows // 100:
        raise RuntimeError("Due-candidate state differs from the fixture; restore it before comparing")
    artifact = ROOT / "build/benchmark/mysql-lab" / datetime.now().strftime("%Y%m%d-%H%M%S")
    artifact.mkdir(parents=True)
    measurements = {}
    reference_hash = None
    for design, index, columns in [("join", "idx_campaign_due", "campaign_id,status,due_at,id"),
                                    ("ids-first", "idx_status_due", "status,due_at,id")]:
        indexes = sql("SELECT DISTINCT index_name FROM information_schema.statistics "
                      "WHERE table_schema=DATABASE() AND table_name='abandoned_cart_coupon_candidates'", database).splitlines()
        if index not in indexes:
            sql(f"CREATE INDEX {index} ON abandoned_cart_coupon_candidates({columns})", database)
        other = "idx_status_due" if design == "join" else "idx_campaign_due"
        if other in indexes:
            sql(f"DROP INDEX {other} ON abandoned_cart_coupon_candidates", database)
        sql("ANALYZE TABLE abandoned_cart_coupon_candidates,abandoned_cart_coupon_campaigns,marketing_actions", database)
        setup, query = "", JOIN + FILTER
        if design == "ids-first":
            setup = "CREATE TEMPORARY TABLE page_ids(id BIGINT PRIMARY KEY);"
            query = (f"INSERT INTO page_ids SELECT id FROM abandoned_cart_coupon_candidates "
                     f"WHERE status='SCHEDULED' AND due_at<='{ANCHOR}' ORDER BY due_at,id LIMIT 500;"
                     + JOIN + "JOIN page_ids selected ON selected.id=c.id ORDER BY c.due_at,c.id")
        times = []
        for iteration in range(repeat + 1):
            output = sql(setup + "SET @lab_started=SYSDATE(6);" + query
                         + ";SELECT 'LAB_ELAPSED_MS',TIMESTAMPDIFF(MICROSECOND,@lab_started,SYSDATE(6))/1000;", database)
            lines = output.splitlines()
            elapsed = float(lines[-1].split("\t")[1])
            result = lines[:-1]
            if len(result) != 500:
                raise RuntimeError(f"Expected 500 rows, got {len(result)}")
            digest = hashlib.sha256("\n".join(result).encode()).hexdigest()
            reference_hash = reference_hash or digest
            if digest != reference_hash:
                raise RuntimeError("Result rows/order differ across query designs")
            if iteration:
                times.append(elapsed)
        plan_query = JOIN + FILTER if design == "join" else JOIN + "JOIN page_ids selected ON selected.id=c.id ORDER BY c.due_at,c.id"
        plan = sql(setup + (query.split(";", 1)[0] + ";" if setup else "")
                   + "EXPLAIN ANALYZE " + plan_query, database)
        (artifact / f"explain-{design}.txt").write_text(plan + "\n")
        measurements[design] = {"server_elapsed_ms": times, "median_ms": statistics.median(times)}
        print(f"{design}: {times}; median={statistics.median(times):.2f}ms", flush=True)
    report = {"boundary": "SQL only, full fields included; no JPA, worker or Kafka",
              "anchor": ANCHOR, "repeat": repeat, "same_ordered_rows_sha256": reference_hash,
              "measurements": measurements,
              "clock": "server wall time across SQL statements, excludes Docker process startup",
              "fixture": sql("SELECT * FROM _fixture", database),
              "history_rows": rows, "due_rows": due,
              "mysql_version": sql("SELECT VERSION()"),
              "note": "Grouped warm runs; not comparable directly with older JDBC/JPA benchmarks"}
    (artifact / "summary.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Artifacts: {artifact.relative_to(ROOT)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("up", "stop", "status"):
        commands.add_parser(command)
    prepare = commands.add_parser("seed")
    prepare.add_argument("dataset", choices=DATABASES)
    prepare.add_argument("--rows", type=int)
    prepare.add_argument("--reset", action="store_true", help="explicitly rebuild only this lab dataset")
    query = commands.add_parser("sql")
    query.add_argument("dataset", choices=DATABASES)
    query.add_argument("--file", type=Path, required=True)
    measure = commands.add_parser("compare-candidates")
    measure.add_argument("--repeat", type=int, default=3)
    args = parser.parse_args()
    if args.command == "up":
        subprocess.run(COMPOSE + ["up", "-d", "--wait"], check=True)
        sql("CREATE DATABASE IF NOT EXISTS benchmark_lab; CREATE TABLE IF NOT EXISTS benchmark_lab.identity"
            "(id INT PRIMARY KEY, lab_id VARCHAR(60)); INSERT IGNORE INTO benchmark_lab.identity VALUES(1,'axon-local-sql-lab')")
        verify_lab()
    elif args.command == "stop":
        subprocess.run(COMPOSE + ["stop"], check=True)
    elif args.command == "seed":
        rows = args.rows if args.rows is not None else (5_000_000 if args.dataset == "candidates" else 1_000_000)
        if rows < 8000 or rows > 10_000_000 or rows % 2000:
            parser.error("rows must be a multiple of 2000 between 8000 and 10000000")
        seed(args.dataset, rows, args.reset)
    elif args.command == "compare-candidates":
        if not 1 <= args.repeat <= 20:
            parser.error("repeat must be between 1 and 20")
        compare(args.repeat)
    else:
        verify_lab()
        if args.command == "sql":
            print(sql(args.file.read_text(), DATABASES[args.dataset]))
        else:
            print("MySQL " + sql("SELECT VERSION()") + ", loopback 127.0.0.1:13306")
            for dataset, database in DATABASES.items():
                if sql(f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{database}'") == "1":
                    print(dataset + ": " + sql("SELECT * FROM _fixture", database))


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError):
            print(error.stderr, file=sys.stderr)
        sys.exit(1)
