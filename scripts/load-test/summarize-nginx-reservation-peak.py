#!/usr/bin/env python3
"""Summarize completed FCFS reservation RPS from axon-nginx access logs."""

import re
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path


ACCESS_LOG = re.compile(
    r'\[(?P<time>[^\]]+)\] "POST /api/v1/entries HTTP/[^\"]+" (?P<status>\d{3}) '
)


def main():
    if len(sys.argv) != 3:
        print("usage: summarize-nginx-reservation-peak.py <access.log> <output.md>", file=sys.stderr)
        return 2

    source_path = Path(sys.argv[1])
    output_path = Path(sys.argv[2])
    per_second = Counter()
    statuses = Counter()

    for line in source_path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = ACCESS_LOG.search(line)
        if not match:
            continue
        second = datetime.strptime(match.group("time"), "%d/%b/%Y:%H:%M:%S %z").isoformat()
        per_second[second] += 1
        statuses[match.group("status")] += 1

    lines = ["# axon-nginx Reservation Completion Peak", ""]
    if not per_second:
        lines.extend([
            "- Status: unavailable",
            "- No `POST /api/v1/entries` access-log records were found in the captured interval.",
        ])
    else:
        peak_second, peak_count = max(per_second.items(), key=lambda item: item[1])
        lines.extend([
            "- Definition: axon-nginx access-log completions per second for `POST /api/v1/entries`.",
            "- This is server-side completion RPS at the Axon proxy, not k6 request-start RPS.",
            f"- Reservation completions: `{sum(per_second.values())}`",
            f"- Peak completion: `{peak_count} req/s` at `{peak_second}`",
            "",
            "## Status Counts",
            "",
            "| status | count |",
            "|---|---:|",
        ])
        for status, count in sorted(statuses.items()):
            lines.append(f"| {status} | {count} |")
        lines.extend([
            "",
            "## Top Completion Seconds",
            "",
            "| second | completed reservations |",
            "|---|---:|",
        ])
        for second, count in sorted(per_second.items(), key=lambda item: item[1], reverse=True)[:5]:
            lines.append(f"| {second} | {count} |")

    output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
