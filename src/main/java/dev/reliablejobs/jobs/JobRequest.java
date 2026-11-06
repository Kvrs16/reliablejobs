package dev.reliablejobs.jobs;

import dev.reliablejobs.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record JobRequest(
    @NotNull Type type, @NotNull @Valid Input input, @Min(0) @Max(3) int retryLimit) {
  @Override
  public String toString() {
    return "JobRequest[type=" + type + ", retryLimit=" + retryLimit + ", input=REDACTED]";
  }

  public enum Type {
    CSV_SUMMARY,
    RECORD_VALIDATION
  }

  public record Input(
      @Min(1) @Max(1000) Integer rows,
      @Min(0) @Max(1000000) Integer seed,
      @Size(min = 1, max = 100) List<@NotNull @Valid DataRecord> records) {
    @Override
    public String toString() {
      return "Input[REDACTED]";
    }
  }

  public record DataRecord(
      @NotNull @Size(max = 80) String name, @NotNull @Size(max = 120) String email, Integer age) {
    @Override
    public String toString() {
      return "DataRecord[REDACTED]";
    }
  }

  public void validateShape() {
    boolean valid =
        type == Type.CSV_SUMMARY
            ? input.rows() != null && input.seed() != null && input.records() == null
            : input.rows() == null
                && input.seed() == null
                && input.records() != null
                && !input.records().isEmpty();
    if (!valid)
      throw new ApiException(400, "INVALID_INPUT", "Input fields do not match the job type");
  }
}
