# Windows setup for V:\reliablejobs

Follow these steps in order. Commands marked PowerShell belong in a PowerShell terminal; commands marked SQL belong inside PostgreSQL's SQL Shell. Do not paste the SQL into PowerShell.

The local check found Java at `C:\Program Files\Common Files\Oracle\Java\javapath\java.exe`. It did not find `psql` on PATH, a PostgreSQL installation in `C:\Program Files\PostgreSQL`, or a registered PostgreSQL service. The built jar was also not found, so build it below. Maven is downloaded by the included wrapper; do not install Maven separately.

## 1. Open PowerShell and check Java

Press the Windows key, type **Windows PowerShell**, and open it. Use this normal terminal for the initial setup and password prompts, rather than VS Code's Debug Console. Call this **Window A**.

```powershell
Set-Location 'V:\reliablejobs'
java -version
```

Expect a version beginning with `21`. Java 21 was detected during the original build. If this command fails or now shows another version, resolve that before building. Keep this terminal open; configuration entered later belongs to this terminal session.

## 2. Install PostgreSQL 18 once

1. Open the [official PostgreSQL Windows download page](https://www.postgresql.org/download/windows/).
2. Click **Download the installer**. The PostgreSQL page directs you to EDB, which hosts the Windows installer.
3. Download the **PostgreSQL 18 Windows x86-64** installer. Choose version 18, not a preview release. No paid product or account is needed for this local project.
4. Run the installer. Accept its Windows permission prompt.
5. Keep installation directory **`C:\Program Files\PostgreSQL\18`**. The commands below assume this exact directory.
6. Keep **PostgreSQL Server** and **Command Line Tools** selected. pgAdmin is optional. Stack Builder is unnecessary for this project.
7. Keep the default data directory.
8. When asked for the database superuser password, create your own password and remember it. This is the **postgres administrator password**, used in step 3.
9. Keep port **5432** and the default locale. If the installer reports that 5432 is already occupied, stop here and investigate rather than silently choosing a different port; this guide uses 5432.
10. Finish installation. Uncheck **Launch Stack Builder** if offered. You do not need extra drivers or subscriptions.

Return to Window A and check:

```powershell
Test-Path 'C:\Program Files\PostgreSQL\18\bin\psql.exe'
Get-Service '*postgres*' | Format-Table Name, Status
```

Expect `True` and a PostgreSQL service with status `Running`. You do not need to change PATH; the next command uses the full executable path. If the service is stopped, open Windows **Services**, find the PostgreSQL service and start it.

## 3. Create the application database once

In Window A:

```powershell
& 'C:\Program Files\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 5432 -U postgres -d postgres
```

At `Password for user postgres:`, enter the **administrator password from the installer**. Characters may not appear as you type; press Enter afterward.

You should now see a prompt such as `postgres=#`. You are inside SQL Shell. Enter these commands **one at a time**:

```sql
CREATE ROLE reliablejobs LOGIN;
\password reliablejobs
```

The second command asks you to enter and repeat a new password. Choose and remember an **application database password** for the `reliablejobs` database user. This is the password you will enter in step 5. It is separate from the administrator password and API login passwords.

Then enter:

```sql
CREATE DATABASE reliablejobs OWNER reliablejobs;
\q
```

Expect `CREATE ROLE` and `CREATE DATABASE` success messages. `\q` returns you to PowerShell. These creation commands are a one-time setup. If a role/database already exists from a previous attempt, do not delete it or blindly rerun the creation commands.

## 4. Build the backend

Back in PowerShell Window A:

```powershell
Set-Location 'V:\reliablejobs'
.\mvnw.cmd -B verify
```

Let it finish. The first run downloads Maven, dependencies and a temporary PostgreSQL test runtime, so it can take several minutes. The test runtime is separate from the PostgreSQL database you just created.

Expect **BUILD SUCCESS**, with unit and integration test summaries. Confirm the executable jar exists:

```powershell
Test-Path 'V:\reliablejobs\target\reliablejobs-1.0.0.jar'
```

Expect `True`. If the build fails, stop here and inspect its error; do not continue to `java -jar` before the jar exists.

## 5. Configure the database connection in Window A

Copy this entire PowerShell block:

```powershell
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:5432/reliablejobs'
$env:DB_USER = 'reliablejobs'
$dbSecret = Read-Host 'Enter the reliablejobs DATABASE password from step 3' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $dbSecret).Password
```

At the prompt enter the **application database password you created with `\password reliablejobs`**. Do not enter the installer administrator password or an API login password here.

These settings apply to Window A and programs launched from it. If you close Window A, repeat step 5 in the replacement terminal. Do not print `$env:DB_PASSWORD` or add it to a committed file.

## 6. Create your first API user once

In the same Window A, run this single line:

```powershell
java -jar .\target\reliablejobs-1.0.0.jar --spring.profiles.active=provision --server.port=0 --reliablejobs.worker.enabled=false --provision.username=alice
```

Wait for `New password (12-64 characters):`. Choose a new **API login password for alice** and enter it. For a simple first setup, use 12-64 ASCII characters. It will not display as you type. This password is not your database password.

Expect `User created.` and a return to the PowerShell prompt. Startup/shutdown logs around that message are normal. Do not run this command again after it succeeds; usernames are unique.

Optional: create an administrator the same way, with a separate password:

```powershell
java -jar .\target\reliablejobs-1.0.0.jar --spring.profiles.active=provision --server.port=0 --reliablejobs.worker.enabled=false --provision.username=admin --provision.role=ADMIN
```

If you see `Run the packaged jar in an interactive terminal`, run the command in the standalone Windows PowerShell window opened from the Start menu, after repeating step 5 there. Do not use the VS Code Debug Console or pipe input into this command.

## 7. Start the backend and leave Window A open

In the same Window A:

```powershell
java -jar .\target\reliablejobs-1.0.0.jar
```

Wait for a log message containing `Started ReliableJobsApplication`. The terminal remains busy while the server runs; this is expected. Leave it running.

You can open `http://127.0.0.1:8080/actuator/health` in a browser. Expect `{"status":"UP"}`. There is no homepage or frontend. An authentication error at another URL does not mean the server failed.

## 8. Open Window B and log in

Open a **second** Windows PowerShell window from the Start menu. Call this **Window B**. Keep Window A running.

In Window B, paste:

```powershell
Set-Location 'V:\reliablejobs'
$base = 'http://127.0.0.1:8080'
$csrf = Invoke-RestMethod "$base/api/auth/csrf" -SessionVariable session
$apiSecret = Read-Host 'Enter the API password you created for alice in step 6' -AsSecureString
$login = @{ username = 'alice'; password = [System.Net.NetworkCredential]::new('', $apiSecret).Password }
Invoke-WebRequest "$base/api/auth/login" -UseBasicParsing -Method Post -WebSession $session -Headers @{'X-CSRF-TOKEN' = $csrf.token} -ContentType 'application/x-www-form-urlencoded' -Body $login
$login.Clear()
$csrf = Invoke-RestMethod "$base/api/auth/csrf" -WebSession $session
```

Enter **alice's API password from step 6** when prompted. Expect a login response with `StatusCode: 204`. The code fetches a fresh CSRF token after authentication because login rotates session security state. Keep using Window B for the following commands so its cookie session variables are preserved.

## 9. Submit a job and read its result

In Window B:

```powershell
$headers = @{'X-CSRF-TOKEN' = $csrf.token; 'Idempotency-Key' = 'my-first-csv-job'}
$job = Invoke-RestMethod "$base/api/jobs" -Method Post -WebSession $session -Headers $headers -ContentType 'application/json' -InFile '.\demo\csv-summary.json'
$job | Format-List
```

Expect a job ID and status. It may already be `SUCCEEDED` because the synthetic job is small. Now inspect its current state:

```powershell
Invoke-RestMethod "$base/api/jobs/$($job.id)" -WebSession $session | Format-List
```

When that shows `SUCCEEDED`, run:

```powershell
Invoke-RestMethod "$base/api/jobs/$($job.id)/attempts" -WebSession $session | Format-List
Invoke-RestMethod "$base/api/jobs/$($job.id)/result" -WebSession $session | Format-List
```

Expect an execution attempt and a numeric summary containing rows, min, max, sum, mean and dataset digest. If result retrieval returns `409 RESULT_NOT_READY`, check the job status again before retrying retrieval.

Run the submission command again with the same key and file to retrieve the same job. To create a different job, use a different Idempotency-Key. To try the other processor:

```powershell
$headers['Idempotency-Key'] = 'my-first-validation-job'
$validationJob = Invoke-RestMethod "$base/api/jobs" -Method Post -WebSession $session -Headers $headers -ContentType 'application/json' -InFile '.\demo\record-validation.json'
Invoke-RestMethod "$base/api/jobs/$($validationJob.id)" -WebSession $session | Format-List
# Run this after its status is SUCCEEDED:
Invoke-RestMethod "$base/api/jobs/$($validationJob.id)/result" -WebSession $session | ConvertTo-Json -Depth 6
```

## Stop and restart later

To stop the backend, select Window A and press **Ctrl+C**. PostgreSQL's Windows service can remain running.

Next time, you do not need to reinstall PostgreSQL, recreate the database or recreate alice. In a new Window A, change to `V:\reliablejobs`, repeat **step 5**, then run the command in **step 7**. Repeat **step 8** in Window B for a fresh login. Rebuild with step 4 after source-code changes.

## Reset a forgotten API login password

This resets alice's API password, preserving her user ID, role and jobs. It does not change any PostgreSQL password. It requires local database access through the configuration from step 5.

1. In Window A, stop the running backend with **Ctrl+C**. Keep that window open so its database environment variables remain available.
2. Rebuild the updated code in Window A:

```powershell
Set-Location 'V:\reliablejobs'
.\mvnw.cmd -B verify
```

3. After BUILD SUCCESS, run in the same window:

```powershell
java -jar .\target\reliablejobs-1.0.0.jar --spring.profiles.active=provision --server.port=0 --reliablejobs.worker.enabled=false --provision.username=alice --provision.reset-password=true
```

4. Enter a new API password at the prompt (12-64 ASCII characters for a simple first setup). Expect `Password reset.`. If the command says the user was not found, create alice using step 6 without the reset flag.
5. Restart the backend:

```powershell
java -jar .\target\reliablejobs-1.0.0.jar
```

6. In Window B, repeat step 8 with the new password. Set the base URL exactly as below; do not paste Markdown link brackets/parentheses into the variable:

```powershell
$base = 'http://127.0.0.1:8080'
```

Restarting the backend clears existing in-memory sessions. The reset flag changes only the password hash; it cannot change a role or create a missing user. There is no public password-reset endpoint.

## Common errors

| Message or symptom | What to check |
| --- | --- |
| `psql is not recognized` | Use the full executable path from step 3, including the leading PowerShell `&`. |
| `Unable to access jarfile` | Change to `V:\reliablejobs`; finish step 4 and confirm its Test-Path returns True. |
| `Connection ... refused` / database health DOWN | PostgreSQL service must be Running on port 5432. |
| `password authentication failed for user reliablejobs` | Repeat step 5 using the database-user password created in step 3, not alice's password. |
| `Could not resolve placeholder DB_PASSWORD` | Step 5 was not run in the same terminal that starts Java. |
| `database reliablejobs does not exist` | Complete the CREATE DATABASE command in step 3. |
| Port 8080 already in use | Check whether another Window A is already running this app. Do not stop unrelated projects. You can use `--server.port=8081` when starting this jar, then set Window B's `$base` to `http://127.0.0.1:8081` before logging in. |
| Login 401 | Use username alice and the API password from step 6. |
| Request 403 | Repeat step 8 to establish cookies and a fresh CSRF token; use the same Window B and base URL. |
| Result 409 | Inspect the job; results are available only for SUCCEEDED. |
| Session expires after being idle | Repeat step 8; the session idle timeout is 20 minutes. |

If a command fails, keep its error text and the step number. Do not share passwords, cookies, or the value of DB_PASSWORD when asking for help.
