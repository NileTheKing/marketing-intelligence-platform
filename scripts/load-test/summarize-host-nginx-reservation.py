#!/usr/bin/env python3
"""Summarize one load-test interval from the VM host nginx logs."""

import re
import sys
from collections import Counter
from pathlib import Path


ACCESS_LOG = re.compile(
    r"^(?P<time>\S+) status=(?P<status>\d{3}) method=POST "
    r"uri=(?P<uri>\S+) rt=(?P<request_time>\S+) "
    r"uct=(?P<upstream_connect_time>\S+) uht=(?P<upstream_header_time>\S+) "
    r"urt=(?P<upstream_response_time>\S+) us=(?P<upstream_status>\S+)"
)

ERROR_PATTERNS = {
    "worker_connections": "worker_connections are not enough",
    "upstream_timeout": "upstream timed out",
    "upstream_connect_failed": "connect() failed",
    "upstream_reset": "reset by peer",
}


def percentile(values, percentile):
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, round((len(ordered) - 1) * percentile)))
    return ordered[index]


def as_seconds(value):
    try:
        return float(value)
    except ValueError:
        return None


def display_seconds(value):
    return "n/a" if value is None else f"{value:.3f}s"


def main():
    if len(sys.argv) != 4:
        print(
            "usage: summarize-host-nginx-reservation.py <access.log> <error.log> <output.md>",
            file=sys.stderr,
        )
        return 2

    access_path, error_path, output_path = map(Path, sys.argv[1:])
    statuses = Counter()
    upstream_statuses = Counter()
    request_times = []
    upstream_connect_times = []
    upstream_response_times = []

    for line in access_path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = ACCESS_LOG.match(line)
        if not match or match.group("uri") != "/api/v1/entries":
            continue
        statuses[match.group("status")] += 1
        upstream_statuses[match.group("upstream_status")] += 1
        for target, field in (
            (request_times, "request_time"),
            (upstream_connect_times, "upstream_connect_time"),
            (upstream_response_times, "upstream_response_time"),
        ):
            value = as_seconds(match.group(field))
            if value is not None:
                target.append(value)

    error_text = error_path.read_text(encoding="utf-8", errors="replace").lower()
    error_counts = {
        label: error_text.count(pattern)
        for label, pattern in ERROR_PATTERNS.items()
    }

    lines = ["# Host nginx Reservation Ingress", ""]
    if not statuses:
        lines.extend([
            "- Status: unavailable",
            "- No `POST /api/v1/entries` host-nginx access records were found in the captured interval.",
        ])
    else:
        lines.extend([
            "- Definition: host-nginx access-log completions for `POST /api/v1/entries` during this k6 interval.",
            f"- Reservation records: `{sum(statuses.values())}`",
            f"- Host request-time p95: `{display_seconds(percentile(request_times, 0.95))}`",
            f"- Host-to-Axon upstream connect p95: `{display_seconds(percentile(upstream_connect_times, 0.95))}`",
            f"- Host-to-Axon upstream response p95: `{display_seconds(percentile(upstream_response_times, 0.95))}`",
            "",
            "## Status Counts",
            "",
            "| host status | count |",
            "|---|---:|",
        ])
        for status, count in sorted(statuses.items()):
            lines.append(f"| {status} | {count} |")
        lines.extend(["", "## Upstream Status Counts", "", "| upstream status | count |", "|---|---:|"])
        for status, count in sorted(upstream_statuses.items()):
            lines.append(f"| {status} | {count} |")

    lines.extend(["", "## Host nginx Error Signals", "", "| signal | count |", "|---|---:|"])
    for label, count in error_counts.items():
        lines.append(f"| {label} | {count} |")

    output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
