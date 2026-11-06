# Verification and known limitations

Verified locally on 2026-11-07 (Asia/Calcutta), Windows amd64, Java 21.0.1, Maven Wrapper 3.9.11, Spring Boot 4.1.2 and native PostgreSQL 18.6. No H2 database, Docker daemon, paid service or existing application database was used.

Command from the repository root:

```powershell
.\mvnw.cmd -B verify
```

**25 tests passed: 4 unit tests and 21 PostgreSQL integration tests. Zero failures, errors or skips.** Maven also built the executable jar `target/reliablejobs-1.0.0.jar`. Exact durations from the delivery run are in [test evidence](test-evidence.txt). These are verification durations, not application throughput/latency benchmarks.

## Coverage evidence

| Requirement | Test evidence |
| --- | --- |
| Authentication and sessions | Real database password hashing/matching; invalid login, login CSRF, session rotation flow and logout invalidation. |
| Authorization / IDOR | Bob cannot read Alice's job/attempts/result or cancel/retry it; Bob's list is empty. Admin metadata is role protected; admin cannot use another owner's result route. |
| Validation | Wrong type/shape, row limits, null records, excessive retry limit, unsupported fields, missing key, 64 KiB body bound, page size bound. |
| Idempotency | Whitespace/property-order normalization, replay headers/status, conflicting payload, per-user key scope. |
| Concurrent submissions | Barrier-coordinated threads prove one persisted job/audit; a competing different payload has one winner and one 409. |
| Safe claiming | Two concurrent claims yield exactly one attempt; a held row lock is skipped without waiting for release. |
| Retries | Injected safe processing failures; exact backoff boundaries using a mutable clock, exhausted budget and rejected manual retry. |
| Manual retry | FAILED with unused budget schedules retry; success/exhaustion reject retry; arbitrary status PATCH rejected. |
| Crash recovery | Computation without finalization, lease expiry, abandoned attempt, fenced stale completion, deterministic recomputation and final success. Exhausted abandoned attempt fails. |
| Cancellation | Pending, retry pending, running request, repeat cancellation, cancelled-result rejection, expired-running recovery; success/failure reject cancellation. |
| Limits | Completion deadline, oversized result rejection, cooperative processor cancellation/deadline. |
| Migration | New disposable PostgreSQL cluster migrates from empty; schema validation, indexes and a forbidden status/lease constraint are checked. |
| Supported processors | Known synthetic CSV numeric result/digest, repeatability, validation findings and omission of invalid values from reports. |
| HTTP and executor | Embedded Tomcat, real HTTP client cookie jar, login, refreshed CSRF, submission, actual bounded executor processing and authorized result retrieval. |
| Observability / docs | Correlation header, safe aggregate health, admin-only metrics, generated OpenAPI, and captured submission logs without record values. |
| Failure handling | Mockito isolates processor failure at the worker boundary and verifies a safe failure code, without forwarding exception text. |

Tests use a fixed/mutable UTC clock, barriers, latches, and executor joins. No arbitrary sleeps. The integration fixture disables scheduled polling and calls the real transactional claim/recovery operations directly; its HTTP smoke test invokes the real executor and joins it. This keeps timing deterministic while exercising database locks, transactions, security filters and actual HTTP. PostgreSQL integration tests run under Failsafe at `verify`; running `test` alone is insufficient.

Machine-generated full reports remain in ignored `target/surefire-reports` and `target/failsafe-reports`. Only sanitized summary evidence is committed. The repository has no CI run results or production measurements to report.

## Known limitations

- Backend only, two job types. No frontend, file uploads, user registration, password recovery, public deployment or arbitrary user code.
- Synthetic CSV is generated and parsed in memory; it is not a general CSV ingestion tool. Record email validation is a basic structural check.
- Native test database startup depends on OS support and free native runtime libraries. Only this Windows amd64 environment was measured. Linux/macOS instructions are provided but were not executed here.
- Crash tests simulate the durable crash boundary and stale-worker return; they do not kill an OS worker process or fault-inject PostgreSQL's commit protocol.
- The HTTP smoke test runs the same Spring Boot application on an ephemeral port; interactive packaged-jar provisioning was not manually exercised in an actual user terminal during verification.
- Cooperative deadlines and cancellation are not forced real-time thread termination. See DELIVERY-GUARANTEES.md for pause/clock assumptions and fencing.
- Leases use application clocks without renewal; multiple instances require synchronized clocks. No fairness or latency guarantee.
- Sessions/metrics live in process memory. Jobs/attempts/results/audits persist in PostgreSQL. Counters are best-effort telemetry and may differ from committed counts after rollback/restart.
- Keys/history/results have indefinite retention; no cleanup or per-user job-count quota. This bounds each job, not cumulative storage.
- No load testing, public security audit, availability measurement or business-impact measurement. SECURITY.md lists assumptions before any public deployment.
