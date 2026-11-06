# Portfolio copy

**ReliableJobs — Task Processing and Execution API**

Built a Java 21 and Spring Boot modular monolith that processes bounded background jobs using PostgreSQL as a durable queue. Implemented session authentication with CSRF protection, owner-scoped APIs, administrator visibility, validated DTOs, Flyway migrations, and persisted execution attempts and audit history.

Designed user-scoped idempotent submission with request fingerprints and database uniqueness, short transactions with `FOR UPDATE SKIP LOCKED` claiming, capped retries with backoff, cooperative cancellation, and lease-based crash recovery with stale-worker fencing. Supported deterministic synthetic CSV summaries and structured-record validation reports with transactional result publication.

Verified authorization, concurrent duplicate submissions, competing workers, retry exhaustion, cancellation and abandoned-execution recovery against native PostgreSQL. Documented at-least-once delivery, transaction boundaries, operational troubleshooting and security assumptions. Added structured logs, correlation IDs, role-protected metrics and authenticated OpenAPI documentation.

**Stack:** Java 21, Spring Boot, Spring Security, Spring Data JPA, PostgreSQL, Flyway, Maven Wrapper, JUnit, Mockito, Micrometer, springdoc OpenAPI.

**Scope:** Personal local-first backend project; no frontend, production deployment, external job side effects, load benchmark, or availability/throughput/business-impact claims. See TEST-RESULTS.md for measured verification, and SECURITY.md for deployment limitations.
