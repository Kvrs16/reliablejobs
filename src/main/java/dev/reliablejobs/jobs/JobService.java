package dev.reliablejobs.jobs;

import dev.reliablejobs.auth.*;
import dev.reliablejobs.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class JobService {
  private static final Logger log = LoggerFactory.getLogger(JobService.class);
  private final JobRepository jobs;
  private final UserRepository users;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final Clock clock;
  private final MeterRegistry metrics;

  public JobService(
      JobRepository jobs,
      UserRepository users,
      JdbcTemplate jdbc,
      JsonMapper json,
      Clock clock,
      MeterRegistry metrics) {
    this.jobs = jobs;
    this.users = users;
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
    this.metrics = metrics;
  }

  private AppUser user(String name) {
    return users
        .findByUsername(name)
        .orElseThrow(() -> new ApiException(401, "UNAUTHENTICATED", "Authentication required"));
  }

  private Job owned(UUID id, String name, boolean lock) {
    Job job = (lock ? jobs.lock(id) : jobs.findById(id)).orElseThrow(JobService::notFound);
    // Admin reads use a separate explicit route. Mutation and result routes remain owner-only.
    if (!job.ownerId.equals(user(name).getId())) throw notFound();
    return job;
  }

  private static ApiException notFound() {
    return new ApiException(404, "NOT_FOUND", "Job not found");
  }

  @Transactional(timeout = 5)
  public JobDtos.Submission submit(String name, String key, JobRequest request) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}"))
      throw new ApiException(
          400,
          "INVALID_IDEMPOTENCY_KEY",
          "Supply a 1-128 character Idempotency-Key using letters, numbers, dot, underscore, colon"
              + " or hyphen");
    request.validateShape();
    String normalized = json.writeValueAsString(request);
    if (normalized.getBytes(StandardCharsets.UTF_8).length > 65536) {
      throw new ApiException(413, "PAYLOAD_TOO_LARGE", "Normalized input exceeds 65536 bytes");
    }
    String fingerprint = sha256(normalized);
    UUID owner = user(name).getId(), id = UUID.randomUUID();
    Instant now = clock.instant();
    // ON CONFLICT waits for a competing insertion to commit, without poisoning this transaction.
    int inserted =
        jdbc.update(
            """
            insert into jobs(id,owner_id,type,input,status,created_at,retry_limit,next_execution_at,idempotency_key,fingerprint)
            values (?,?,?,?,'PENDING',?,?,?,?,?) on conflict(owner_id,idempotency_key) do nothing
            """,
            id,
            owner,
            request.type().name(),
            normalized,
            java.sql.Timestamp.from(now),
            request.retryLimit(),
            java.sql.Timestamp.from(now),
            key,
            fingerprint);
    Job job = jobs.findByOwnerIdAndIdempotencyKey(owner, key).orElseThrow();
    if (!job.fingerprint.equals(fingerprint))
      throw new ApiException(
          409, "IDEMPOTENCY_CONFLICT", "This key was already used with different input");
    if (inserted == 1) {
      audit(job, owner, "SUBMITTED");
      metrics.counter("reliablejobs.submitted").increment();
    }
    log.atInfo()
        .addKeyValue("jobId", job.id)
        .addKeyValue("replayed", inserted == 0)
        .log("Submission resolved");
    return new JobDtos.Submission(JobDtos.View.of(job), inserted == 0);
  }

  static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Transactional(readOnly = true)
  public JobDtos.View get(UUID id, String name) {
    return JobDtos.View.of(owned(id, name, false));
  }

  @Transactional(readOnly = true)
  public JobDtos.Listing list(String name, JobStatus status, int page, int size, boolean admin) {
    if (page < 0 || page > 10000 || size < 1 || size > 100)
      throw new ApiException(400, "INVALID_PAGE", "Page must be 0-10000 and size 1-100");
    Page<Job> result =
        jobs.search(
            admin ? null : user(name).getId(),
            status,
            PageRequest.of(
                page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
    return new JobDtos.Listing(
        result.map(JobDtos.View::of).toList(),
        page,
        size,
        result.getTotalElements(),
        result.getTotalPages());
  }

  @Transactional(readOnly = true)
  public List<JobDtos.Attempt> attempts(UUID id, String name) {
    owned(id, name, false);
    return readAttempts(id);
  }

  List<JobDtos.Attempt> readAttempts(UUID id) {
    return jdbc.query(
        "select * from execution_attempts where job_id=? order by number",
        (r, n) ->
            new JobDtos.Attempt(
                r.getObject("id", UUID.class),
                r.getInt("number"),
                r.getTimestamp("started_at").toInstant(),
                r.getTimestamp("completed_at") == null
                    ? null
                    : r.getTimestamp("completed_at").toInstant(),
                r.getString("outcome"),
                r.getString("failure_code")),
        id);
  }

  @Transactional(readOnly = true)
  public Object result(UUID id, String name) {
    Job job = owned(id, name, false);
    if (job.status != JobStatus.SUCCEEDED)
      throw new ApiException(409, "RESULT_NOT_READY", "Result is available only after success");
    return json.readTree(job.result);
  }

  @Transactional(timeout = 5)
  public JobDtos.View cancel(UUID id, String name) {
    Job j = owned(id, name, true);
    if (j.status == JobStatus.CANCELLED) return JobDtos.View.of(j);
    if (j.status == JobStatus.PENDING || j.status == JobStatus.RETRY_PENDING) {
      j.cancellationRequested = true;
      j.status = JobStatus.CANCELLED;
      j.completedAt = clock.instant();
      audit(j, j.ownerId, "CANCELLED");
    } else if (j.status == JobStatus.RUNNING) {
      if (!j.cancellationRequested) audit(j, j.ownerId, "CANCELLATION_REQUESTED");
      j.cancellationRequested = true;
    } else throw new ApiException(409, "INVALID_TRANSITION", "Completed jobs cannot be cancelled");
    return JobDtos.View.of(j);
  }

  @Transactional(timeout = 5)
  public JobDtos.View retry(UUID id, String name) {
    Job j = owned(id, name, true);
    if (j.status != JobStatus.FAILED || j.attemptCount >= j.retryLimit + 1)
      throw new ApiException(
          409, "RETRY_NOT_ALLOWED", "Job must be failed with remaining execution budget");
    j.status = JobStatus.RETRY_PENDING;
    j.completedAt = null;
    j.nextExecutionAt = clock.instant().plus(backoff(j.attemptCount));
    j.failureCode = null;
    audit(j, j.ownerId, "MANUAL_RETRY");
    metrics.counter("reliablejobs.retried").increment();
    return JobDtos.View.of(j);
  }

  @Transactional(readOnly = true)
  public JobDtos.View adminGet(UUID id) {
    return JobDtos.View.of(jobs.findById(id).orElseThrow(JobService::notFound));
  }

  @Transactional(readOnly = true)
  public List<JobDtos.Attempt> adminAttempts(UUID id) {
    adminGet(id);
    return readAttempts(id);
  }

  @Transactional(readOnly = true)
  public List<JobDtos.Audit> audit(UUID id) {
    adminGet(id);
    return jdbc.query(
        "select * from audit_events where job_id=? order by occurred_at,id",
        (r, n) ->
            new JobDtos.Audit(
                r.getObject("id", UUID.class),
                r.getObject("actor_id", UUID.class),
                r.getString("event"),
                r.getTimestamp("occurred_at").toInstant()),
        id);
  }

  void audit(Job job, UUID actor, String event) {
    jdbc.update(
        "insert into audit_events(id,job_id,actor_id,event,occurred_at) values (?,?,?,?,?)",
        UUID.randomUUID(),
        job.id,
        actor,
        event,
        java.sql.Timestamp.from(clock.instant()));
  }

  static Duration backoff(int attempt) {
    return Duration.ofSeconds(Math.min(60, 5L << Math.min(4, Math.max(0, attempt - 1))));
  }
}
