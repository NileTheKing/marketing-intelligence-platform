#!/usr/bin/env python3
"""Connect client-side payment stages to the final Core persistence counts."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


def metric_count(metrics: dict[str, object], name: str) -> str:
    item = metrics.get(name, {})
    if not isinstance(item, dict):
        return "n/a"
    value = item.get("count")
    return str(value) if value is not None else "n/a"


def log_value(text: str, label: str) -> str:
    match = re.search(rf"^{re.escape(label)}:\s*(.+)$", text, re.MULTILINE)
    return match.group(1).strip() if match else "n/a"


def difference(left: str, right: str) -> str:
    try:
        return str(int(float(left)) - int(float(right)))
    except ValueError:
        return "n/a"


def main() -> None:
    if len(sys.argv) not in (4, 5):
        raise SystemExit("usage: summarize-payment-funnel.py SUMMARY.json DOMAIN.log OUTPUT.md [CORE_IDLE_STATUS]")

    with Path(sys.argv[1]).open(encoding="utf-8") as source:
        metrics = json.load(source).get("metrics", {})
    domain = Path(sys.argv[2]).read_text(encoding="utf-8")
    idle_status = sys.argv[4] if len(sys.argv) == 5 else None
    if idle_status == "0":
        idle_result = "idle"
    elif idle_status is None:
        idle_result = "unknown (not provided)"
    else:
        idle_result = f"not idle (wait status {idle_status})"

    reservation_success = metric_count(metrics, "fcfs_success_count")
    prepare_attempted = metric_count(metrics, "payment_prepare_attempted")
    prepare_success = metric_count(metrics, "payment_prepare_success")
    confirm_attempted = metric_count(metrics, "payment_confirm_attempted")
    confirm_success = metric_count(metrics, "payment_confirm_success")
    entries = log_value(domain, "DB entries")
    purchases = log_value(domain, "DB purchases")

    lines = [
        "# Payment Funnel",
        "",
        f"Core Kafka lag and pending Hikari idle result after k6: `{idle_result}`.",
        "A successful confirm is the client-visible handoff to the asynchronous Core path.",
        "",
        "| stage | count |",
        "|---|---:|",
        f"| Reservation accepted (`POST /entries` 200) | {reservation_success} |",
        f"| Payment prepare attempted | {prepare_attempted} |",
        f"| Payment prepare succeeded (200) | {prepare_success} |",
        f"| Payment confirm attempted | {confirm_attempted} |",
        f"| Payment confirm succeeded (200) | {confirm_success} |",
        f"| Core Entry records | {entries} |",
        f"| Core Purchase records | {purchases} |",
        "",
        "## Reconciliation",
        "",
        f"- Confirm success minus Purchase records: `{difference(confirm_success, purchases)}`",
        f"- Confirm success minus Entry records: `{difference(confirm_success, entries)}`",
        "",
        "A non-zero difference must be investigated with command DLT counts and Core logs before calling it a message-loss defect.",
    ]
    Path(sys.argv[3]).write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
