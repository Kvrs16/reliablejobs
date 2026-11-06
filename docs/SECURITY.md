# Security assumptions

The initial application is a local, same-origin backend on loopback. It uses Spring Security's supported session/form-login flow, server-side sessions, session ID rotation on authentication, logout invalidation, and a 20-minute idle timeout. There is no custom token signing or third-party identity service. Users are provisioned through an explicit interactive local command. BCrypt strength 12 hashes are stored; demo passwords and database credentials are not committed. The provisioning command rejects passwords beyond BCrypt's 72-byte input bound.

CSRF remains enabled for login, logout, submit, cancel and retry. `/api/auth/csrf` supplies the session-bound token; fetch a new token after login. The session cookie is HttpOnly and SameSite=Strict, with Secure controlled by `COOKIE_SECURE` (false only for loopback HTTP). CORS is not enabled. Default Spring Security response headers remain active; no blanket CSRF exclusions are used. A future same-origin browser client must preserve cookies and send the CSRF header.

Every owner job read, result read, attempt read and mutation checks authenticated database ownership. A UUID does not grant permission. Other owners' IDs produce the same 404 as missing IDs. Administrator metadata routes are explicitly role-protected; administrator membership alone does not grant owner-route result access or mutation. The process and PostgreSQL administrator can read database contents; application ownership checks cannot defend against those administrators.

Submission accepts only two enumerated, bounded processors. There is no filesystem path, uploaded executable, URL fetching, SQL expression or shell-command input. Bean validation bounds counts and lengths; request guarding bounds the body to 64 KiB, including requests without Content-Length. Results are bounded in application code and by PostgreSQL checks. The processors enforce a five-second cooperative deadline. Tomcat upload/read and PostgreSQL statement/lock timeouts limit ordinary stalled operations; these are not a hard real-time sandbox against JVM pauses.

Input and results are stored in plaintext in the local database; use synthetic data only. Validation reports expose positions/field names rather than the invalid values. Job DTOs omit input, lease secrets, fingerprints and password data. Logs never intentionally record passwords, cookies, session IDs, input bodies or exception messages from processing. Correlation IDs accept only a restricted character set to prevent log injection. Keep JDBC bind logging, HTTP payload tracing, and credential debugging off.

Management endpoints: public health returns only aggregate status; metrics and all other management paths require ADMIN, with only health/metrics exposed. OpenAPI requires authentication. Service errors return safe codes and correlation IDs. Audit events retain IDs, actor (null for worker), event and time, without payloads. Audits are transactionally tied to state changes but not tamper-proof against database administrators.

## Limits of this personal project

- No registration, password recovery, MFA, login rate limiter, account lockout, or session-sharing service. Loopback access and controlled local user provisioning are explicit assumptions. Do not expose it publicly as-is.
- No per-user queue quota, disk retention policy, encrypted-at-rest application fields or backup automation. Input/result bounds limit each job, not total database growth.
- The application DB role owns the development schema for migration convenience. A hardened deployment needs separate least-privilege migration/runtime roles and HTTPS/Secure cookies.
- User role/password changes made directly in the DB do not revoke existing sessions automatically. Restart or invalidate sessions when administering credentials.
- A maintained Java 21 patch release is recommended; the locally measured run used the preinstalled 21.0.1 JDK. Dependency compatibility tests are not a vulnerability audit or penetration test.
