"""Exercise the running local demo with unique events and restore its receiver mode."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
from pathlib import Path
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

API = "http://127.0.0.1:8080"
WAREHOUSE = "http://127.0.0.1:8081"


def request(url, body=None, key=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    data = None if body is None else json.dumps(body).encode()
    try:
        response = urlopen(Request(url, data=data, headers=headers), timeout=6)
    except HTTPError as error:
        response = error
    with response:
        content = response.read()
        if response.status != expected:
            raise AssertionError(f"{url} returned {response.status}, expected {expected}: {content[:300]!r}")
        return json.loads(content) if content else None


def set_mode(mode):
    return request(WAREHOUSE + "/demo/mode/" + mode, {})


def submission(order):
    return {"destination": "warehouse", "eventType": "order.created", "payload": {"orderId": order}}


def accept(order):
    return request(API + "/events", submission(order), order, 202)["id"]


def wait_for(event_id, predicate, timeout=45):
    deadline = time.monotonic() + timeout
    while True:
        history = request(API + "/events/" + event_id)
        if predicate(history):
            return history
        if time.monotonic() >= deadline:
            raise AssertionError(f"Timed out waiting for {event_id}: {history}")
        time.sleep(0.1)


def delivered(event_id):
    return wait_for(event_id, lambda h: h["event"]["state"] == "DELIVERED")


def evidence(name, history):
    event_id = history["event"]["id"]
    receipt = request(WAREHOUSE + "/demo/orders/" + event_id)
    assert receipt["saved"], f"Missing warehouse receipt for {event_id}"
    return {"scenario": name, "history": history, "warehouseReceipt": receipt}


def run():
    original = request(WAREHOUSE + "/demo")["mode"]
    run_id = str(uuid4())
    results = []
    try:
        set_mode("HEALTHY")
        order = run_id + "-idempotency"
        with ThreadPoolExecutor(max_workers=8) as pool:
            ids = list(pool.map(lambda _: accept(order), range(16)))
        assert len(set(ids)) == 1, "Concurrent replays created different events"
        request(API + "/events", submission(order + "-changed"), order, 409)
        results.append(evidence("16 concurrent identical submissions and conflicting reuse", delivered(ids[0])))

        set_mode("OFFLINE")
        event_id = accept(run_id + "-outage")
        wait_for(event_id, lambda h: any(a["httpStatus"] == 503 for a in h["attempts"]))
        set_mode("HEALTHY")
        history = delivered(event_id)
        assert history["event"]["totalAttempts"] >= 2
        results.append(evidence("receiver outage followed by automatic recovery", history))

        set_mode("DELAY_FIRST_ACK")
        before = request(WAREHOUSE + "/demo")["orders"]
        history = delivered(accept(run_id + "-acknowledgment"))
        outcomes = [a["outcome"] for a in history["attempts"]]
        assert "UNKNOWN" in outcomes and outcomes[-1] == "ACKNOWLEDGED", outcomes
        result = evidence("receiver commits before sender times out", history)
        result["totalWarehouseOrderDelta"] = request(WAREHOUSE + "/demo")["orders"] - before
        results.append(result)

        set_mode("OFFLINE")
        event_id = accept(run_id + "-replay")
        dead = wait_for(event_id, lambda h: h["event"]["state"] == "DEAD")
        request(API + "/events/" + event_id + "/replay", {}, expected=202)
        set_mode("HEALTHY")
        history = delivered(event_id)
        assert history["event"]["replayCount"] == 1
        assert history["attempts"][:len(dead["attempts"])] == dead["attempts"]
        result = evidence("retry exhaustion and manual replay with preserved history", history)
        result["attemptsBeforeReplay"] = len(dead["attempts"])
        results.append(result)
    finally:
        set_mode(original)
    return {"utc": datetime.now(timezone.utc).isoformat(), "runId": run_id,
            "api": API, "receiver": WAREHOUSE, "scenarios": results,
            "scope": "Live HTTP checks. Worker lease races are covered by the separate PostgreSQL integration suite."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("benchmarks/local/demo.json"))
    args = parser.parse_args()
    report = run()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    for item in report["scenarios"]:
        print("PASS", item["scenario"])
    print("Saved", args.output)
