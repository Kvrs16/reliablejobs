package dev.reliablejobs.jobs;

import java.time.Clock;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Pure, bounded computations; no files, network, scripts, or external writes. */
@Component
public class JobProcessor {
  private final JsonMapper json;
  private final Clock clock;

  public JobProcessor(JsonMapper json, Clock clock) {
    this.json = json;
    this.clock = clock;
  }

  public String execute(JobDtos.Claim claim, BooleanSupplier cancelled) {
    Object result =
        switch (claim.request().type()) {
          case CSV_SUMMARY -> summarize(claim, cancelled);
          case RECORD_VALIDATION -> validateRecords(claim, cancelled);
        };
    checkpoint(claim, cancelled);
    return json.writeValueAsString(result);
  }

  private Object summarize(JobDtos.Claim claim, BooleanSupplier cancelled) {
    var input = claim.request().input();
    // Generate a synthetic CSV in memory, then summarize its numeric value column.
    var random = new Random(input.seed());
    StringBuilder csv = new StringBuilder("id,value\n");
    for (int i = 1; i <= input.rows(); i++) {
      checkpoint(claim, cancelled);
      csv.append(i).append(',').append(random.nextInt(1001)).append('\n');
    }
    IntSummaryStatistics stats = new IntSummaryStatistics();
    for (String line : csv.toString().split("\n")) {
      checkpoint(claim, cancelled);
      if (!line.startsWith("id,"))
        stats.accept(Integer.parseInt(line.substring(line.indexOf(',') + 1)));
    }
    return Map.of(
        "rows",
        stats.getCount(),
        "min",
        stats.getMin(),
        "max",
        stats.getMax(),
        "sum",
        stats.getSum(),
        "mean",
        stats.getAverage(),
        "seed",
        input.seed(),
        "datasetSha256",
        JobService.sha256(csv.toString()));
  }

  private Object validateRecords(JobDtos.Claim claim, BooleanSupplier cancelled) {
    List<Map<String, Object>> errors = new ArrayList<>();
    var records = claim.request().input().records();
    for (int i = 0; i < records.size(); i++) {
      checkpoint(claim, cancelled);
      var r = records.get(i);
      List<String> fields = new ArrayList<>();
      if (r.name().isBlank()) fields.add("name");
      if (!r.email().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) fields.add("email");
      if (r.age() == null || r.age() < 0 || r.age() > 130) fields.add("age");
      if (!fields.isEmpty()) errors.add(Map.of("record", i + 1, "invalidFields", fields));
    }
    return Map.of(
        "totalRecords",
        records.size(),
        "validRecords",
        records.size() - errors.size(),
        "errors",
        errors);
  }

  private void checkpoint(JobDtos.Claim claim, BooleanSupplier cancelled) {
    if (Thread.currentThread().isInterrupted() || !clock.instant().isBefore(claim.deadline()))
      throw new ProcessingFailure("EXECUTION_TIMEOUT", true);
    if (cancelled.getAsBoolean()) throw new ProcessingFailure("CANCELLED", false);
  }

  public static class ProcessingFailure extends RuntimeException {
    final String code;
    final boolean retryable;

    public ProcessingFailure(String code, boolean retryable) {
      super(code);
      this.code = code;
      this.retryable = retryable;
    }
  }
}
