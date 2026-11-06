package dev.reliablejobs.jobs;

import java.time.Instant;
import java.util.*;

public final class JobDtos {
  private JobDtos() {}

  public record View(
      UUID id,
      UUID ownerId,
      JobRequest.Type type,
      JobStatus status,
      Instant createdAt,
      Instant startedAt,
      Instant completedAt,
      int attemptCount,
      int retryLimit,
      Instant nextExecutionAt,
      boolean cancellationRequested,
      String failureCode,
      Integer resultBytes) {
    static View of(Job j) {
      return new View(
          j.id,
          j.ownerId,
          j.type,
          j.status,
          j.createdAt,
          j.startedAt,
          j.completedAt,
          j.attemptCount,
          j.retryLimit,
          j.nextExecutionAt,
          j.cancellationRequested,
          j.failureCode,
          j.resultBytes);
    }
  }

  public record Submission(View job, boolean replayed) {}

  public record Listing(
      List<View> content, int page, int size, long totalElements, int totalPages) {}

  public record Attempt(
      UUID id,
      int number,
      Instant startedAt,
      Instant completedAt,
      String outcome,
      String failureCode) {}

  public record Audit(UUID id, UUID actorId, String event, Instant occurredAt) {}

  public record Claim(
      UUID jobId, UUID attemptId, UUID token, JobRequest request, Instant deadline) {}
}
