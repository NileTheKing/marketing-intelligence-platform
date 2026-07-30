#!/usr/bin/env python3
"""Summarize per-second reservation request start/completion peaks from k6 JSON."""

import json
import sys
from collections import defaultdict
from pathlib import Path


def metric_name(event):
    data = event.get("data", {})
    return data.get("metric") or event.get("metric") or data.get("name")


def summarize(path, metric):
    buckets = defaultdict(float)
    total = 0.0

    with path.open(encoding="utf-8") as source:
        for line in source:
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            if event.get("type") != "Point" or metric_name(event) != metric:
                continue

            data = event.get("data", {})
            timestamp = data.get("time")
            if not timestamp:
                continue
            value = float(data.get("value", 0))
            second = timestamp[:19]
            buckets[second] += value
            total += value

    if not buckets:
        return None

    ordered = sorted(buckets.items())
    peak_second, peak_value = max(ordered, key=lambda item: item[1])
    return total, peak_second, peak_value, ordered


def main():
    if len(sys.argv) != 3:
        print("usage: summarize-k6-request-peaks.py <k6-events.json> <output.md>", file=sys.stderr)
        return 2

    event_path = Path(sys.argv[1])
    output_path = Path(sys.argv[2])
    started = summarize(event_path, "reservation_attempt_started")
    completed = summarize(event_path, "reservation_attempt_completed")

    lines = ["# Reservation Request Peak", ""]
    if started is None or completed is None:
        lines.extend([
            "- Status: unavailable",
            "- Required k6 metrics were not found in the JSON output.",
        ])
    else:
        start_total, start_second, start_peak, start_series = started
        complete_total, complete_second, complete_peak, complete_series = completed
        lines.extend([
            "- Definition: one-second buckets from k6 custom Counter timestamps.",
            "- Ingress peak: reservation attempts emitted by the load generator immediately before the reservation POST.",
            "- Completion peak: reservation HTTP calls returned to the load generator, regardless of business outcome.",
            "",
            f"- Reservation attempts: `{int(start_total)}`",
            f"- Reservation completions: `{int(complete_total)}`",
            f"- Peak ingress: `{start_peak:g} req/s` at `{start_second}`",
            f"- Peak completion: `{complete_peak:g} req/s` at `{complete_second}`",
            "",
            "## Top Ingress Seconds",
            "",
            "| second | reservation attempts |",
            "|---|---:|",
        ])
        for second, value in sorted(start_series, key=lambda item: item[1], reverse=True)[:5]:
            lines.append(f"| {second} | {value:g} |")
        lines.extend([
            "",
            "## Top Completion Seconds",
            "",
            "| second | reservation completions |",
            "|---|---:|",
        ])
        for second, value in sorted(complete_series, key=lambda item: item[1], reverse=True)[:5]:
            lines.append(f"| {second} | {value:g} |")

    output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
