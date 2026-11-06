# ReliableJobs: Task Processing and Execution API

A personal Java 21 / Spring Boot modular monolith for submitting bounded background jobs, inspecting attempts, retrieving owner-authorized results, and requesting cancellation or retry. PostgreSQL is the durable queue. There is no frontend, message broker, cloud service, or paid API.

## Requirements

- A maintained Java 21 JDK (for example free Eclipse Temurin) and Git.
- PostgreSQL 18 for application use; the test suite starts its own native PostgreSQL 18.6 process and needs no database installation or containers.
- Internet on the first Maven run to download dependencies. Maven Wrapper is included.

See [verified versions](docs/VERSIONS.md). All setup below is local. Install PostgreSQL using the [official download instructions](https://www.postgresql.org/download/). On Windows, add the installation's `bin` directory to PATH or use its SQL Shell. On Linux use the distribution's free PostgreSQL packages. No container runtime is required.

## 1. Create a local database

Connect as your local database administrator: `psql -U postgres`. Enter its password interactively, then run:

```sql
CREATE ROLE reliablejobs LOGIN;
\password reliablejobs
CREATE DATABASE reliablejobs OWNER reliablejobs;
```

Choose your own database password at the prompt. No application password or credentials are bundled. The development database owner runs Flyway; a production deployment would separate migration and runtime roles. Keep PostgreSQL bound to localhost.

## 2. Build and test

PowerShell, from this repository:

```powershell
.\mvnw.cmd -B verify
```

Linux/macOS:

```sh
chmod +x mvnw
./mvnw -B verify
```

`test` runs unit tests; **`verify` also runs PostgreSQL integration tests**. Reports are in `target/surefire-reports` and `target/failsafe-reports`. See [measured results](docs/TEST-RESULTS.md). Tests create a disposable database, apply Flyway from empty, and never use your application database. Native test binaries support common amd64 systems; ARM may require the corresponding Zonky binary dependency. Linux must run tests as a non-root user. Windows may need the free Visual C++ runtime described in Zonky's troubleshooting guide.

## 3. Set local database configuration

PowerShell (the password prompt avoids saving a plaintext secret in shell history):

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/reliablejobs'
$env:DB_USER = 'reliablejobs'
$dbSecret = Read-Host 'Database password' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $dbSecret).Password
```

Linux/macOS, in a Bash terminal:

```bash
export DB_URL=jdbc:postgresql://localhost:5432/reliablejobs
export DB_USER=reliablejobs
read -rs -p 'Database password: ' DB_PASSWORD; echo
export DB_PASSWORD
```

The application receives the secret through its environment; do not commit environment files. Remove the environment variable after use (`Remove-Item Env:DB_PASSWORD` / `unset DB_PASSWORD`).

## 4. Provision users interactively

After `verify` creates the jar, run each command in an interactive terminal. Enter a distinct password when prompted (12-64 characters, at most 72 UTF-8 bytes). The process creates the user and exits. Reusing a username fails rather than resetting its password.

```sh
java -jar target/reliablejobs-1.0.0.jar --spring.profiles.active=provision --server.port=0 --reliablejobs.worker.enabled=false --provision.username=alice
java -jar target/reliablejobs-1.0.0.jar --spring.profiles.active=provision --server.port=0 --reliablejobs.worker.enabled=false --provision.username=bob
java -jar target/reliablejobs-1.0.0.jar --spring.profiles.active=provision --server.port=0 --reliablejobs.worker.enabled=false --provision.username=admin --provision.role=ADMIN
```

Do not use an IDE console that has no `System.console()` for provisioning. There is deliberately no public registration or user-management HTTP endpoint.

## 5. Run

```sh
java -jar target/reliablejobs-1.0.0.jar
```

The service listens on **127.0.0.1:8080**. Flyway migrates and Hibernate validates; schema auto-update is disabled. Use `http://127.0.0.1:8080` consistently for cookie origin. Local HTTP uses HttpOnly, SameSite=Strict session cookies. A future HTTPS deployment must set `COOKIE_SECURE=true` and preserve same-origin routing.

## 6. Login and submit a job (PowerShell)

```powershell
$base = 'http://127.0.0.1:8080'
$csrf = Invoke-RestMethod "$base/api/auth/csrf" -SessionVariable session
$credential = Get-Credential -UserName alice -Message 'ReliableJobs login'
$login = @{username=$credential.UserName; password=$credential.GetNetworkCredential().Password}
Invoke-WebRequest "$base/api/auth/login" -Method Post -WebSession $session -Headers @{'X-CSRF-TOKEN'=$csrf.token} -Body $login
# Authentication rotates the session and clears its previous CSRF token.
$csrf = Invoke-RestMethod "$base/api/auth/csrf" -WebSession $session
$headers = @{'X-CSRF-TOKEN'=$csrf.token; 'Idempotency-Key'='demo-csv-1'}
$job = Invoke-RestMethod "$base/api/jobs" -Method Post -WebSession $session -Headers $headers -ContentType application/json -InFile demo/csv-summary.json
$job
Invoke-RestMethod "$base/api/jobs/$($job.id)" -WebSession $session
Invoke-RestMethod "$base/api/jobs/$($job.id)/attempts" -WebSession $session
# Fetch once status is SUCCEEDED; otherwise 409 RESULT_NOT_READY is expected.
Invoke-RestMethod "$base/api/jobs/$($job.id)/result" -WebSession $session
Invoke-RestMethod "$base/api/jobs?status=SUCCEEDED&page=0&size=10" -WebSession $session
```

Repeat the same submission and key to get the existing job (`200`, replay header); change the input with the same key to get `409`. New work needs a new key. A cancelled or failed job is still replayed under its original key. Job input is not returned in list or detail DTOs.

```powershell
# Use on a PENDING, RETRY_PENDING, or RUNNING job; terminal success/failure returns 409.
Invoke-RestMethod "$base/api/jobs/$($job.id)/cancel" -Method Post -WebSession $session -Headers @{'X-CSRF-TOKEN'=$csrf.token}
# Eligible FAILED job only; does not increase its attempt budget.
Invoke-RestMethod "$base/api/jobs/$($job.id)/retry" -Method Post -WebSession $session -Headers @{'X-CSRF-TOKEN'=$csrf.token}
```

The included jobs usually finish too quickly to cancel manually. Deterministic tests demonstrate pending/running cancellation and recovery without adding a slow or destructive public job type.

## Documentation

- [Architecture, transaction boundaries, state and schema diagrams](docs/ARCHITECTURE.md)
- [API, DTOs, errors and input limits](docs/API.md)
- [Delivery guarantees and crash windows](docs/DELIVERY-GUARANTEES.md)
- [Security assumptions](docs/SECURITY.md)
- [Operations and failure investigation](docs/OPERATIONS.md)
- [Five-minute demonstration](docs/DEMO.md)
- [Test results and limitations](docs/TEST-RESULTS.md)
- [Verified portfolio copy](docs/PORTFOLIO-COPY.md)

Authenticated OpenAPI JSON: `/v3/api-docs`; Swagger UI: `/swagger-ui/index.html`. These are same-origin session-protected endpoints; after API login, open the UI with the same browser session or retrieve the JSON with your authenticated client. Login is Spring Security's form endpoint; see the API document for its contract. Swagger UI is supplementary; PowerShell is the complete supported walkthrough.
