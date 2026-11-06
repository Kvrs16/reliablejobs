# Delivery guarantees

This is **at-least-once processing with bounded retries**, not exactly-once execution. A committed eligible job can execute again after an expired lease even if an earlier worker already computed the same result. Eventual execution assumes a running worker, reachable database, finite contention, and remaining budget; it is not a guarantee of success.

## Submission idempotency

Every submission requires an `Idempotency-Key` of 1-128 ASCII letters, numbers, dot, underscore, colon or hyphen. The key is scoped to the authenticated database user ID, not globally. It is stored with the job and a SHA-256 fingerprint of the parsed, validated, consistently serialized request DTO. JSON whitespace and property ordering do not change the fingerprint. Absent optional fields normalize to null and omitted `retryLimit` to zero. Record list order remains significant; record strings are preserved without trimming or case folding. Unknown fields and invalid shapes are rejected before persistence.

Concurrent identical submissions create one job through the database uniqueness constraint. The loser waits for the winning transaction to finish, reads its committed record and returns that job. If the winner rolls back, the other insertion may succeed. A different fingerprint under the same owner/key yields 409. SHA-256 collisions are a theoretical limitation of fingerprint comparison.

Keys, fingerprints, attempts, audit rows and results are **retained indefinitely in this version**, including for terminal/cancelled jobs. There is no automated cleanup or deletion endpoint. Replaying a terminal key returns its current job (200, `Idempotency-Replayed: true`), not a new execution and not a frozen original HTTP response. New submission gets 201 and `Location`. Retrying an existing job uses the retry endpoint and is a separate state command. If a future retention policy deletes a key, that policy must explicitly state that the old key can create new work after expiry. Do not manually delete just keys while keeping related jobs.

## Crash windows

| Crash point | Durable state and recovery |
| --- | --- |
| Before submission commit | Job and audit roll back. Client can repeat the key. |
| After submission commit, before response | Repeat key returns the committed job. |
| During claim transaction | Job/attempt/audit roll back together. Another worker can claim it. |
| After claim, before or during computation | RUNNING remains until lease expires; recovery marks attempt ABANDONED, schedules retry or fails if exhausted. |
| After computing a result, before completion transaction commits | Result exists only in memory and is lost. Recovery can recompute it. |
| During result/attempt/audit commit | PostgreSQL commits all or rolls back all. If the worker cannot tell whether commit succeeded, the durable job state is authoritative. |
| After completion commit, before worker returns | SUCCEEDED cannot be claimed again. Result is already durable. |
| Old worker returns after recovery/reclaim | Its token is invalid; completion returns false without publishing or altering the new attempt. Even before recovery, an expired lease rejects completion. |

CSV_SUMMARY uses a fixed seed and Java's specified `Random` algorithm; identical input produces the same synthetic dataset and summary. RECORD_VALIDATION emits only record positions and invalid field names. Both perform only bounded in-memory computation. The single result is committed together with SUCCEEDED; they do not send emails, charge money, modify external records, or perform any other duplicate external effect. Processor code changes can change results on a later attempt; no cross-version bitwise stability is promised.

Leases use UTC application clocks (30 seconds) and execution deadlines use the same injected clock (5 seconds). This local design does not renew leases. The lease has slack for short claim/finalize transactions. An application pause or clock jump can expire one; recovery is safe but may cause duplicate computation. Multiple instances require reasonably synchronized clocks; fencing remains essential. PostgreSQL row locks prevent simultaneous claims of the same eligible row, but they do not prevent an old, paused worker from computing after expiry. Clock faults are not fully solved here.

Cancellation is cooperative. A RUNNING cancellation response means the request was recorded, not that a thread instantly stopped. The processor checks periodically and the completion transaction checks again. If recovery sees a cancellation flag, it cancels instead of requeueing. A result committed before cancellation wins the race and cancellation is rejected. There is no unsafe `Thread.stop`, arbitrary command execution, or interruption of unrelated work.

Counters and logs are operational telemetry, not transactional accounting. They reset at process restart; a counter increment shortly before rollback can differ from durable counts. Use the jobs, attempts and audits tables for authoritative history. Database durability depends on PostgreSQL configuration and disk/backup health. No availability, latency, throughput, fairness, or permanent retention guarantee is asserted.
