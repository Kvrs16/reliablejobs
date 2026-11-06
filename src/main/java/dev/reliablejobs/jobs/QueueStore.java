package dev.reliablejobs.jobs;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Every public operation is one short transaction. No user work executes here. */
@Service
public class QueueStore {
  private static final Logger log = LoggerFactory.getLogger(QueueStore.class);
  public static final Duration LEASE = Duration.ofSeconds(30),
      EXECUTION_LIMIT = Duration.ofSeconds(5);
  private final JobRepository jobs;
  private final JobService service;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final JsonMapper json;
  private final MeterRegistry metrics;

  public QueueStore(
      JobRepository jobs,
      JobService service,
      JdbcTemplate jdbc,
      Clock clock,
      JsonMapper json,
      MeterRegistry metrics) {
    this.jobs = jobs;
    this.service = service;
    this.jdbc = jdbc;
    this.clock = clock;
    this.json = json;
    this.metrics = metrics;
  }

  @Transactional(timeout = 5)
  public Optional<JobDtos.Claim> claim() {
    Instant now = clock.instant();
    return jobs.claimable(now)
        .map(
            j -> {
              j.status = JobStatus.RUNNING;
              j.attemptCount++;
              j.startedAt = now;
              j.failureCode = null;
              j.leaseToken = UUID.randomUUID();
              j.leaseUntil = now.plus(LEASE);
              UUID attempt = UUID.randomUUID();
              jdbc.update(
                  "insert into execution_attempts(id,job_id,number,lease_token,started_at,outcome)"
                      + " values (?,?,?,?,?,'RUNNING')",
                  attempt,
                  j.id,
                  j.attemptCount,
                  j.leaseToken,
                  java.sql.Timestamp.from(now));
              service.audit(j, null, "CLAIMED");
              log.atInfo()
                  .addKeyValue("jobId", j.id)
                  .addKeyValue("attemptId", attempt)
                  .log("Job claimed");
              return new JobDtos.Claim(
                  j.id,
                  attempt,
                  j.leaseToken,
                  json.readValue(j.input, JobRequest.class),
                  now.plus(EXECUTION_LIMIT));
            });
  }

  @Transactional(readOnly = true)
  public boolean shouldStop(JobDtos.Claim c) {
    return jobs.findById(c.jobId())
        .map(
            j ->
                j.status != JobStatus.RUNNING
                    || !c.token().equals(j.leaseToken)
                    || j.cancellationRequested
                    || !j.leaseUntil.isAfter(clock.instant()))
        .orElse(true);
  }

  @Transactional(timeout = 5)
  public boolean complete(JobDtos.Claim c, String result, String failureCode, boolean retryable) {
    Job j = jobs.lock(c.jobId()).orElseThrow();
    Instant now = clock.instant();
    if (j.status != JobStatus.RUNNING
        || !c.token().equals(j.leaseToken)
        || !j.leaseUntil.isAfter(now)) return false;
    String outcome;
    if (j.cancellationRequested) {
      j.status = JobStatus.CANCELLED;
      j.completedAt = now;
      outcome = "CANCELLED";
      failureCode = null;
    } else if (failureCode == null && result != null && now.isBefore(c.deadline())) {
      int size = result.getBytes(StandardCharsets.UTF_8).length;
      if (size > 65536) {
        failureCode = "RESULT_TOO_LARGE";
        retryable = false;
        outcome = fail(j, failureCode, retryable, now);
      } else {
        j.status = JobStatus.SUCCEEDED;
        j.completedAt = now;
        j.result = result;
        j.resultBytes = size;
        outcome = "SUCCEEDED";
        metrics.counter("reliablejobs.successful").increment();
      }
    } else {
      if (failureCode == null) {
        failureCode = "EXECUTION_TIMEOUT";
        retryable = true;
      }
      outcome = fail(j, failureCode, retryable, now);
    }
    jdbc.update(
        "update execution_attempts set outcome=?,failure_code=?,completed_at=? where id=? and"
            + " lease_token=?",
        outcome,
        failureCode,
        java.sql.Timestamp.from(now),
        c.attemptId(),
        c.token());
    j.leaseToken = null;
    j.leaseUntil = null;
    service.audit(j, null, j.status.name());
    log.atInfo()
        .addKeyValue("jobId", j.id)
        .addKeyValue("attemptId", c.attemptId())
        .addKeyValue("outcome", outcome)
        .addKeyValue("failureCode", failureCode)
        .log("Attempt completed");
    return true;
  }

  private String fail(Job j, String code, boolean retryable, Instant now) {
    j.failureCode = code;
    if (retryable && j.attemptCount < j.retryLimit + 1) {
      j.status = JobStatus.RETRY_PENDING;
      j.nextExecutionAt = now.plus(JobService.backoff(j.attemptCount));
      metrics.counter("reliablejobs.retried").increment();
    } else {
      j.status = JobStatus.FAILED;
      j.completedAt = now;
      metrics.counter("reliablejobs.failed").increment();
    }
    return "FAILED";
  }

  @Transactional(timeout = 5)
  public int recover() {
    Instant now = clock.instant();
    List<Job> abandoned = jobs.abandoned(now);
    for (Job j : abandoned) {
      UUID attemptId =
          jdbc.queryForObject(
              "update execution_attempts set"
                  + " outcome='ABANDONED',failure_code='LEASE_EXPIRED',completed_at=? where"
                  + " job_id=? and lease_token=? and outcome='RUNNING' returning id",
              UUID.class,
              java.sql.Timestamp.from(now),
              j.id,
              j.leaseToken);
      if (j.cancellationRequested) {
        j.status = JobStatus.CANCELLED;
        j.completedAt = now;
      } else fail(j, "LEASE_EXPIRED", true, now);
      j.leaseUntil = null;
      j.leaseToken = null;
      service.audit(j, null, "LEASE_RECOVERED");
      log.atWarn()
          .addKeyValue("jobId", j.id)
          .addKeyValue("attemptId", attemptId)
          .log("Expired execution lease recovered");
    }
    return abandoned.size();
  }
}
