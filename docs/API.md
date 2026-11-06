# API contract

Base: `http://127.0.0.1:8080`. Requests/responses use JSON except form login. Session cookie `JSESSIONID`; all mutations require the session's `X-CSRF-TOKEN`. No bearer token or custom JWT. No CORS grants; use the same origin. Job IDs are UUIDs, timestamps ISO-8601 UTC, and enum values uppercase.

| Method and path | Success | Meaning |
| --- | --- | --- |
| GET `/api/auth/csrf` | 200 | Anonymous/session CSRF token `{headerName,token}`; preserve cookies. |
| POST `/api/auth/login` | 204 | `application/x-www-form-urlencoded` fields `username`, `password`; CSRF required. Invalid credentials 401. Session ID rotates; fetch a new CSRF token. |
| POST `/api/auth/logout` | 204 | CSRF required; session invalidated. |
| POST `/api/jobs` | 201 / 200 | Submit / replay. Required `Idempotency-Key`; JSON schema below. `Location` and `Idempotency-Replayed` headers. |
| GET `/api/jobs?status=PENDING&page=0&size=20` | 200 | Own jobs only. Optional status, page 0-10000, size 1-100. Sort by createdAt descending then ID ascending. |
| GET `/api/jobs/{id}` | 200 | Own job DTO. |
| GET `/api/jobs/{id}/attempts` | 200 | Own execution attempts, ordered by number. At most four. |
| GET `/api/jobs/{id}/result` | 200 | Own JSON result, only for SUCCEEDED; otherwise 409. |
| POST `/api/jobs/{id}/cancel` | 200 / 202 | Immediate cancellation or already cancelled / request recorded for RUNNING. No body. |
| POST `/api/jobs/{id}/retry` | 202 | FAILED with budget remaining only; retains original input and job ID. No body. |
| GET `/api/admin/jobs` | 200 | ADMIN only; global listing, same pagination/filter options. |
| GET `/api/admin/jobs/{id}` | 200 | ADMIN operational job metadata. |
| GET `/api/admin/jobs/{id}/attempts` | 200 | ADMIN attempts. |
| GET `/api/admin/jobs/{id}/audit` | 200 | ADMIN audit history. No sensitive payloads. |
| GET `/actuator/health` | 200 / 503 | Public aggregate status, no components/details. |
| GET `/actuator/metrics[/{name}]` | 200 | ADMIN only. Other management endpoints not exposed. |
| GET `/v3/api-docs`, `/swagger-ui/index.html` | 200 | Authenticated OpenAPI/Swagger. |

Nonexistent and other users' IDs both return 404. Even administrators use owner-only routes for result access and mutations; ADMIN visibility does not silently bypass ownership on those routes. No public user creation, arbitrary status edits, input edits, lease controls, file uploads, URLs, SQL, scripts or commands are accepted.

## Submission DTO

```json
{"type":"CSV_SUMMARY","input":{"rows":100,"seed":42},"retryLimit":2}
```

CSV_SUMMARY requires rows 1-1000 and seed 0-1,000,000; records must be absent/null. It generates `id,value` CSV in memory with values 0-1000, then summarizes it. Result: rows, seed, min, max, sum, mean and datasetSha256. This is a synthetic dataset demonstration, not a general uploaded-CSV parser.

```json
{"type":"RECORD_VALIDATION","input":{"records":[{"name":"Synthetic A","email":"a@example.test","age":25},{"name":"","email":"invalid","age":-1}]},"retryLimit":1}
```

RECORD_VALIDATION requires 1-100 records; rows/seed absent/null. Each record requires non-null name (max 80 characters), non-null email (max 120), and nullable integer age. Empty names, malformed simple email structure, and ages missing or outside 0-130 are **report findings**, not HTTP validation errors. Overlong strings, null records, unsupported fields/types, and wrong input shape are 400. Validation findings mean the job **succeeded** in producing its report; they do not trigger retries. The email check is deliberately a basic structural rule, not RFC-complete mailbox verification.

Result: `totalRecords`, `validRecords`, and `errors: [{record:2, invalidFields:["name","email","age"]}]`. Record positions are one-based; sensitive field values are not copied into the report.

Both types allow retryLimit 0-3, default zero. Entire submission body and normalized input are limited to 65,536 bytes; results to 65,536 UTF-8 bytes. Unknown JSON properties are rejected. Total work is bounded by input counts and a five-second cooperative deadline.

## Responses

Job DTO fields: `id`, `ownerId`, `type`, `status`, `createdAt`, `startedAt`, `completedAt`, `attemptCount`, `retryLimit`, `nextExecutionAt`, `cancellationRequested`, `failureCode`, `resultBytes`. Input, fingerprints, session identifiers, internal lease data and result contents are omitted. `resultBytes` describes the compact JSON result's UTF-8 size. No result download filename or file path exists.

Listing: `{content:[...],page:0,size:20,totalElements:1,totalPages:1}`. Offset pagination can shift when concurrent submissions occur; it is not a snapshot cursor.

Attempt DTO: `{id,number,startedAt,completedAt,outcome,failureCode}`. Outcomes: RUNNING, SUCCEEDED, FAILED, ABANDONED, CANCELLED. An attempt may fail while the parent job is RETRY_PENDING. Safe failure codes include PROCESSING_ERROR, EXECUTION_TIMEOUT, RESULT_TOO_LARGE, LEASE_EXPIRED. No exception messages or stack traces are exposed.

Errors use `application/problem+json` with status, detail, code and correlationId (MVC errors also include the standard Problem Detail fields):

```json
{"type":"about:blank","status":409,"detail":"This key was already used with different input","code":"IDEMPOTENCY_CONFLICT","correlationId":"demo_123"}
```

Main status codes: 400 invalid input/key/page, 401 unauthenticated/bad credentials, 403 role/CSRF rejection, 404 hidden/not found, 405 unsupported method, 409 conflicting key or state, 413 submission too large, 415 unsupported content type, 500 safe unexpected error. Retrying a transient 500 submission should reuse its original key. Send `X-Correlation-ID` (1-64 letters, numbers, `_`, `-`) to preserve an ID, or let the server generate one. The response echoes it.

The generated OpenAPI covers controller DTO endpoints; login/logout are Spring Security filters and their exact contracts are documented above. Swagger's cookie scheme does not replace login or automatically supply CSRF. Prefer the README walkthrough for a full session flow.
