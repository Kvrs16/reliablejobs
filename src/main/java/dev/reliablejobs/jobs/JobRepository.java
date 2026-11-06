package dev.reliablejobs.jobs;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface JobRepository extends JpaRepository<Job, UUID> {
  Optional<Job> findByOwnerIdAndIdempotencyKey(UUID ownerId, String key);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select j from Job j where j.id=:id")
  Optional<Job> lock(UUID id);

  @Query(
      value =
          "select * from jobs where status in ('PENDING','RETRY_PENDING') and"
              + " next_execution_at<=:now order by next_execution_at,created_at,id limit 1 for"
              + " update skip locked",
      nativeQuery = true)
  Optional<Job> claimable(Instant now);

  @Query(
      value =
          "select * from jobs where status='RUNNING' and lease_until<=:now order by lease_until"
              + " limit 20 for update skip locked",
      nativeQuery = true)
  List<Job> abandoned(Instant now);

  @Query(
      "select j from Job j where (:owner is null or j.ownerId=:owner) and (:status is null or"
          + " j.status=:status)")
  Page<Job> search(UUID owner, JobStatus status, Pageable page);
}
