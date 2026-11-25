"""Measure a healthy local run and reconcile each accepted event with a warehouse receipt."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import time
from urllib.request import Request, urlopen
from uuid import uuid4


API = "http://127.0.0.1:8080"
WAREHOUSE = "http://127.0.0.1:8081"


def request(url, body=None, key=None):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    data = None if body is None else json.dumps(body).encode()
    with urlopen(Request(url, data=data, headers=headers), timeout=10) as response:
        return json.load(response)


def run(count, clients, deadline_seconds):
    if request(WAREHOUSE + "/demo")["mode"] != "HEALTHY":
        raise SystemExit("Set the warehouse to HEALTHY before measuring.")
    run_id = str(uuid4())

    def submit(index):
        key = f"{run_id}-{index}"
        body = {"destination": "warehouse", "eventType": "order.created", "payload": {"orderId": key}}
        return request(API + "/events", body, key)["id"]

    started = time.monotonic()
    with ThreadPoolExecutor(max_workers=clients) as pool:
        ids = list(pool.map(submit, range(count)))
        ingest_seconds = time.monotonic() - started
        pending = set(ids)
        histories = {}
        while pending:
            batch = sorted(pending)
            for event_id, history in zip(batch, pool.map(lambda i: request(API + "/events/" + i), batch)):
                histories[event_id] = history
                if history["event"]["state"] in ("DELIVERED", "DEAD"):
                    pending.remove(event_id)
            if not pending or time.monotonic() - started >= deadline_seconds:
                break
            time.sleep(0.25)
        drain_seconds = time.monotonic() - started
        saved = sum(pool.map(lambda i: request(WAREHOUSE + "/demo/orders/" + i)["saved"], ids))

    states = Counter()
    attempts = Counter()
    latencies = []
    for history in histories.values():
        event = history["event"]
        states[event["state"]] += 1
        attempts.update(attempt["outcome"] for attempt in history["attempts"])
        if event["state"] == "DELIVERED":
            created = datetime.fromisoformat(event["createdAt"].replace("Z", "+00:00"))
            acknowledged = datetime.fromisoformat(event["updatedAt"].replace("Z", "+00:00"))
            latencies.append((acknowledged - created).total_seconds() * 1000)
    latencies.sort()
    return {
        "utc": datetime.now(timezone.utc).isoformat(), "runId": run_id, "requested": count,
        "clients": clients, "uniqueAccepted": len(set(ids)), "states": dict(states),
        "attempts": dict(attempts), "verifiedWarehouseReceipts": saved,
        "ingestEventsPerSecond": round(count / ingest_seconds, 2),
        "secondsUntilObservedDrain": round(drain_seconds, 3),
        "p95AcceptanceToAckMs": round(latencies[math.ceil(0.95 * len(latencies)) - 1], 2) if latencies else None
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--events", type=int, default=100)
    parser.add_argument("--clients", type=int, default=4)
    parser.add_argument("--deadline", type=int, default=120)
    parser.add_argument("--output", type=Path, default=Path("benchmarks/local/latest.json"))
    args = parser.parse_args()
    if not 1 <= args.events <= 10000 or not 1 <= args.clients <= 32 or args.deadline < 1:
        parser.error("Use 1–10000 events, 1–32 clients, and a positive deadline.")
    summary = run(args.events, args.clients, args.deadline)
    report = json.dumps(summary, indent=2)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(report + "\n", encoding="utf-8")
    print(report)
    if (summary["uniqueAccepted"] != args.events or summary["states"].get("DELIVERED", 0) != args.events
            or summary["verifiedWarehouseReceipts"] != args.events):
        raise SystemExit(1)
