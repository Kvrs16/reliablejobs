# Five-minute demonstration

Preparation: finish README setup, provision alice/bob/admin with your own passwords, start PostgreSQL and application, and run `verify`. Have three authenticated client sessions available using the README flow. Use the synthetic JSON files in `demo/` only.

| Time | Demonstration | Expected evidence |
| --- | --- | --- |
| 0:00-1:00 | Explain monolith and PostgreSQL-backed queue. Fetch CSRF, login as alice, fetch new token. | 204 login; session cookie protected, no JWT. Show architecture diagram. |
| 1:00-2:00 | Submit `demo/csv-summary.json` with key `demo-csv-1`; inspect job, attempts, result. | 201, job ID, RUNNING or already SUCCEEDED, one attempt, numeric summary/dataset digest. Poll manually if necessary; fast jobs may already be terminal. |
| 2:00-3:00 | Replay identical key/body; modify seed under same key; submit record-validation with new key. | Replay 200 and same ID; changed input 409. Validation job SUCCEEDED with two invalid records and no copied invalid values. |
| 3:00-4:00 | Use bob's session to fetch alice's job/result/attempts and cancel it; query bob's own list. Use admin to inspect global metadata/attempts/audit. | Bob gets 404 and an empty own list; admin metadata is visible. Completed job cancellation is 409, not a pretend successful stop. |
| 4:00-5:00 | Show test report and targeted crash/cancellation tests; explain lease token, backoff and result commit. Inspect admin metrics/JSON worker logs. | PostgreSQL tests cover competing claims, retries, stale completion rejection and running cancellation. Show measured test counts from TEST-RESULTS.md, not performance claims. |

For record validation, reuse the README `$session` and refreshed `$csrf`:

```powershell
$records = Invoke-RestMethod "$base/api/jobs" -Method Post -WebSession $session -Headers @{'X-CSRF-TOKEN'=$csrf.token;'Idempotency-Key'='demo-validation-1'} -ContentType application/json -InFile demo/record-validation.json
Invoke-RestMethod "$base/api/jobs/$($records.id)/result" -WebSession $session
```

For an immediate pending cancellation without racing a fast processor, start the app with `--reliablejobs.worker.enabled=false`, submit and cancel a job, then restart with workers enabled. PENDING cancellation returns 200 and no attempt is created. This is a local configuration demonstration, not a hidden API to disable other users' work. Running cancellation and abandoned execution are reliably shown by deterministic tests, rather than promising a human can interrupt a millisecond computation.

No frontend, real customer dataset, paid service, external side effect, or benchmark is part of this demonstration.
