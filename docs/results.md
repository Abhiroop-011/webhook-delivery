# Verification and measurements

## Verification summary

| Check | Observed result |
|---|---|
| Standard Maven build | `.\mvnw.cmd verify` completed successfully from PowerShell. |
| Unit and local HTTP tests | 7 passed, covering retry decisions, signed bytes, HTTP timeouts, and redirects. |
| PostgreSQL integration tests | 6 passed, covering concurrent intake, competing claims, lease recovery, stale completion, lost acknowledgment, and manual replay. |
| Actual worker crash | Both workers were killed with SIGKILL during delivery. After restart, lease recovery recorded UNKNOWN and a second attempt received HTTP 204. The warehouse receipt was verified. |
| Live demo checks | Four scenarios passed against the Compose stack and its real PostgreSQL database. |
| Live browser checks and recording | The dashboard passed all four scenario checks against the running service. A 54-second silent recording was captured and its playback verified. |
| Workload replay | Both 1,000-event runs acknowledged every event and verified its warehouse receipt. |

The Maven suite summaries are preserved in [tests.json](measurements/tests.json). Full generated reports are in `target/surefire-reports/` and `target/failsafe-reports/` after running the suite. An integration test forces expiry and reclaim to verify stale-worker behavior deterministically. A separate process-kill experiment is recorded in [worker-crash.json](measurements/worker-crash.json).

In that live experiment, event `b8819338-732a-43d5-b06f-cb578556c34f` was claimed at 09:11:01.350522 UTC. After both worker processes were killed and restarted, the unfinished attempt was recorded as UNKNOWN at 09:11:10.662974 UTC. Attempt 2 received HTTP 204, and the sender recorded DELIVERED at 09:11:11.564708 UTC. The receiver confirmed the same event ID. The script restored the receiver to HEALTHY. This is one observed crash-recovery run.

## Live failure checks

The API, two worker processes, and the warehouse ran in Docker with PostgreSQL 16. The client connected through the published loopback ports.

| Scenario | Observed behavior |
|---|---|
| 16 concurrent submissions of one identity | One event ID returned to all callers. Conflicting reuse returned HTTP 409. |
| Receiver returns HTTP 503 | One retryable attempt, then an acknowledged attempt after recovery. |
| Receiver saves the order and delays its response | UNKNOWN followed by ACKNOWLEDGED. The receiver's total order count increased by one during this isolated scenario. |
| Retry budget exhausted, then manual replay | Five failed attempts, then a successful sixth attempt. The event ID and first five attempt records were preserved. |

[demo.json](measurements/demo.json) contains the event identities, timestamps, every observed attempt, and receiver receipts. Reproduce independently of other submissions with `python scripts/verify_demo.py`. The script temporarily changes the receiver's behavior and restores its initial mode.

## Local burst measurements

Both runs used 1,000 events, four concurrent submitting clients, and two sequential delivery workers. The first run overlapped Maven integration tests on the same machine. The repeat started after the Maven suite completed. These runs illustrate sensitivity to shared-host load.

| Measurement | First run | Repeat |
|---|---:|---:|
| Unique accepted events | 1,000 | 1,000 |
| Acknowledged events | 1,000 | 1,000 |
| Verified warehouse receipts | 1,000 | 1,000 |
| Unknown attempts that required retry | 2 | 0 |
| Ingest events per second | 187.69 | 287.58 |
| Seconds until drain was observed | 44.500 | 12.383 |
| p95 acceptance to acknowledgment | 38.416 s | 8.263 s |

The raw client outputs are [first run](measurements/healthy-1000.json) and [repeat](measurements/healthy-1000-repeat.json). [environment.json](measurements/environment.json) records the available environment details.

Ingestion throughput measures how quickly the client received acceptance responses. It does not measure delivery throughput. The p95 includes time waiting behind other events in the queue and uses database creation and acknowledgment timestamps. Drain time includes the client's polling overhead. The receiver is local, the runs are short bursts, and both services and the client share a development machine.

The client uses `127.0.0.1`. A diagnostic on this Windows host took approximately 2.24 seconds with Python's `localhost` connection and 8 ms with the explicit IPv4 address. Using the explicit address avoids including that connection delay in every request.

## Reproduce

Start the Compose stack, select **Accept deliveries**, and run:

```powershell
.\mvnw.cmd verify
python scripts/benchmark.py --events 1000 --clients 4
```

Run the benchmark after the Maven command finishes. The integration suite creates its own disposable PostgreSQL instance. The benchmark creates unique events in the running demo and writes its report to `benchmarks/local/latest.json`. It exits with an error if any submitted identity fails reconciliation.

