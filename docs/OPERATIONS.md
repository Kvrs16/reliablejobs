# Operations and failure investigation

Start PostgreSQL, then the jar using the README environment variables. Default HTTP bind is loopback only. `GET /actuator/health` exposes only UP/DOWN. Database connectivity is part of health; DOWN is 503. Do not route management endpoints through an unauthenticated public proxy.

## Structured logs and metrics

Console output uses Spring Boot's Logstash JSON format. HTTP logs include a sanitized/generated correlationId, method and response status. Worker logs include jobId and attemptId on claiming/completion/recovery. Async processing is linked by durable job/attempt IDs; a request's MDC is deliberately not carried over after its response. Unexpected errors log the exception's class rather than its potentially sensitive message. Successful completion of an in-memory processor alone is not proof of a committed job; inspect database state.

ADMIN session requests can inspect:

```text
GET /actuator/metrics/reliablejobs.submitted
GET /actuator/metrics/reliablejobs.successful
GET /actuator/metrics/reliablejobs.failed
GET /actuator/metrics/reliablejobs.retried
```

Counters are created lazily when an event first occurs. Submitted counts newly created jobs, not replays. Successful and failed count terminal transitions, not every failed attempt. Retried counts transitions into RETRY_PENDING, both automatic and manual. These are per-process observations, reset at restart, and are not durable billing/transactional totals. No Prometheus or paid monitoring subscription is required. Current durable totals can be queried by status through the admin listing.

## Reproduce failures safely

Most supported jobs are deterministic and fast; do not add a public failure/sleep/script endpoint merely for a demo. Run the targeted PostgreSQL tests, which inject controlled failure outcomes and advance a test clock:

```powershell
.\mvnw.cmd -B '-Dit.test=ReliableJobsIT#retryBackoffAndExhaustionAreBounded' verify
.\mvnw.cmd -B '-Dit.test=ReliableJobsIT#abandonedExecutionIsRecoveredAndStaleCompletionRejected' verify
.\mvnw.cmd -B '-Dit.test=ReliableJobsIT#recoveryHonorsCancellationAndTerminalJobsRejectCancellation' verify
```

In Bash use `./mvnw` with the same quoted arguments. Unit tests also run with these commands. The test database is independent and disposable. The crash test computes a result, intentionally omits completion, advances beyond the lease, recovers, rejects stale completion, and verifies deterministic recomputation on a new attempt. It simulates the crash window at the persistence boundary; it does not terminate the OS process.

For a live stopped worker, pending work remains in PostgreSQL. Restart the application to resume. Previously RUNNING jobs remain leased for up to 30 seconds from claim, then a poll (normally every 500 ms) recovers up to 20. Recovery is delayed by database outages, contention, executor availability, or a paused process. Never repair a queue by blindly updating status or deleting attempts.

## Investigate a failed job

1. Preserve response correlationId and job ID. Locate the request's JSON log; inspect the job via its owner route or admin metadata route.
2. Read `/api/jobs/{id}/attempts` as owner or the admin equivalent. Compare safe failure code, attempt count, retry budget and next eligible time.
3. As administrator, read `/api/admin/jobs/{id}/audit`. Match attemptId in worker logs. LEASE_EXPIRED means completion was not durably recorded within the lease, not proof that computation never ran.
4. Check health and PostgreSQL connectivity. A worker database error leaves the durable state for later polling/recovery. Inspect PostgreSQL logs and available connections; application pool is capped at eight.
5. If RETRY_PENDING, wait until its documented due time. If FAILED with unused budget, owner retry schedules another attempt; if exhausted, fix the cause and submit new work with a new idempotency key. Never reset count or claim exactly-once recovery.

Useful read-only local SQL (application-owner/admin access):

```sql
SELECT id,status,attempt_count,retry_limit,next_execution_at,lease_until,failure_code
FROM jobs ORDER BY created_at DESC LIMIT 20;
SELECT id,job_id,number,outcome,started_at,completed_at,failure_code
FROM execution_attempts ORDER BY started_at DESC LIMIT 20;
SELECT pid,state,wait_event_type,wait_event FROM pg_stat_activity
WHERE datname=current_database();
```

Avoid selecting/logging input or result bodies during troubleshooting. Row locks should be short; claims skip competing locks. Commands/finalization use row locking, a three-second lock timeout and five-second statement/transaction limits. A timeout may return a safe 500; clients may replay submissions with the original idempotency key.

## Shutdown and maintenance

Stop with Ctrl+C. Web shutdown is graceful; workers get up to ten seconds to finish, then receive interruption. Work not durably finalized is recovered by lease expiry after restart. Back up PostgreSQL if you need durable personal demo history; no backups are configured by this repository. Flyway changes should be new versioned migrations once deployed; never edit a migration already applied to a real database. Hibernate validates instead of updating schema.

No cleanup endpoint exists. Database size, health, process uptime, Java patch maintenance and disk capacity remain the local operator's responsibility. There are no measured production throughput/latency claims.
