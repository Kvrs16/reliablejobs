package dev.reliablejobs.jobs;

import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "reliablejobs.worker.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class Worker {
  private static final Logger log = LoggerFactory.getLogger(Worker.class);
  private final QueueStore store;
  private final JobProcessor processor;
  // Two running tasks, no queued claimed jobs. Claim happens inside the allocated worker.
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          Thread.ofPlatform().name("job-worker-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());

  public Worker(QueueStore store, JobProcessor processor) {
    this.store = store;
    this.processor = processor;
  }

  @Scheduled(fixedDelayString = "${reliablejobs.worker.poll-ms:500}")
  public void poll() {
    try {
      store.recover();
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("errorType", e.getClass().getSimpleName())
          .log("Recovery poll failed");
      return;
    }
    for (int i = 0; i < 2; i++) {
      try {
        executor.execute(this::claimAndRun);
      } catch (RejectedExecutionException full) {
        break;
      }
    }
  }

  private void claimAndRun() {
    try {
      store.claim().ifPresent(this::run);
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("errorType", e.getClass().getSimpleName())
          .log("Worker transaction failed; leases permit recovery");
    }
  }

  private void run(JobDtos.Claim claim) {
    try (MDC.MDCCloseable job = MDC.putCloseable("jobId", claim.jobId().toString());
        MDC.MDCCloseable attempt = MDC.putCloseable("attemptId", claim.attemptId().toString())) {
      String result = null, code = null;
      boolean retryable = true;
      try {
        // Check cancellation at most once per 100 ms; finalization always checks under a row lock.
        AtomicLong next = new AtomicLong(0);
        result =
            processor.execute(
                claim,
                () -> {
                  long now = System.nanoTime();
                  if (now < next.get()) return false;
                  next.set(now + TimeUnit.MILLISECONDS.toNanos(100));
                  return store.shouldStop(claim);
                });
      } catch (JobProcessor.ProcessingFailure e) {
        code = e.code;
        retryable = e.retryable;
      } catch (RuntimeException e) {
        code = "PROCESSING_ERROR";
        log.atWarn()
            .addKeyValue("errorType", e.getClass().getSimpleName())
            .log("Processing failed");
      }
      store.complete(claim, result, code, retryable);
    }
  }

  @PreDestroy
  public void shutdown() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
