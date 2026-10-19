#!/usr/bin/env python3
"""Summarize per-container cgroup CPU throttling for one load-test interval."""

from __future__ import annotations

import csv
import sys
from pathlib import Path


def read_stats(path: Path) -> dict[str, dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as source:
        return {row["container"]: row for row in csv.DictReader(source)}


def delta(after: str, before: str) -> str:
    try:
        return str(int(after) - int(before))
    except (TypeError, ValueError):
        return "n/a"


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("usage: summarize-cgroup-cpu-stat.py BEFORE.csv AFTER.csv OUTPUT.md")

    before = read_stats(Path(sys.argv[1]))
    after = read_stats(Path(sys.argv[2]))
    output = Path(sys.argv[3])

    rows = []
    for container in sorted(set(before) | set(after)):
        start = before.get(container, {})
        end = after.get(container, {})
        source = end.get("source", start.get("source", "unavailable"))
        rows.append(
            (
                container,
                source,
                delta(end.get("usage", "n/a"), start.get("usage", "n/a")),
                delta(end.get("nr_throttled", "n/a"), start.get("nr_throttled", "n/a")),
                delta(end.get("throttled", "n/a"), start.get("throttled", "n/a")),
            )
        )

    unit = "microseconds" if any(source == "cgroup-v2-usec" for _, source, *_ in rows) else "nanoseconds"
    lines = [
        "# Container CPU Throttling",
        "",
        "Difference between the snapshots immediately before k6 and after Core becomes idle.",
        f"`throttled` unit: {unit}. `n/a` means cgroup stats were unavailable.",
        "",
        "| container | cgroup | CPU usage delta | throttled periods | throttled time delta |",
        "|---|---|---:|---:|---:|",
    ]
    lines.extend(
        f"| {container} | {source} | {usage} | {periods} | {throttled} |"
        for container, source, usage, periods, throttled in rows
    )
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
