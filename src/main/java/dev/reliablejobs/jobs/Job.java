package dev.reliablejobs.jobs;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs")
public class Job {
  @Id UUID id;

  @Column(nullable = false)
  UUID ownerId;

  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  JobRequest.Type type;

  @Column(columnDefinition = "text", nullable = false)
  String input;

  @Enumerated(EnumType.STRING)
  @Column(length = 24, nullable = false)
  JobStatus status;

  Instant createdAt;
  Instant startedAt;
  Instant completedAt;
  int attemptCount;
  int retryLimit;
  Instant nextExecutionAt;
  Instant leaseUntil;
  UUID leaseToken;
  boolean cancellationRequested;

  @Column(length = 40)
  String failureCode;

  @Column(columnDefinition = "text")
  String result;

  Integer resultBytes;

  @Column(length = 128, nullable = false)
  String idempotencyKey;

  @Column(length = 64, nullable = false)
  String fingerprint;

  @Version long version;

  protected Job() {}
}
