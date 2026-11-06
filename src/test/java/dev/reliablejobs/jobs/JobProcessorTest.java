package dev.reliablejobs.jobs;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JobProcessorTest {
  final JsonMapper json = JsonMapper.builder().build();
  final Instant now = Instant.parse("2026-01-01T00:00:00Z");
  final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  final JobProcessor processor = new JobProcessor(json, clock);

  JobDtos.Claim claim(JobRequest request) {
    return new JobDtos.Claim(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), request, now.plusSeconds(5));
  }

  @Test
  void summarizesKnownSyntheticCsvAndIsRepeatable() {
    var c =
        claim(new JobRequest(JobRequest.Type.CSV_SUMMARY, new JobRequest.Input(1, 42, null), 0));
    String result = processor.execute(c, () -> false);
    assertThat(result).isEqualTo(processor.execute(c, () -> false));
    var node = json.readTree(result);
    assertThat(node.get("rows").asInt()).isEqualTo(1);
    assertThat(node.get("sum").asInt()).isEqualTo(260); // Java Random(42), first nextInt(1001)
    assertThat(node.get("min").asInt()).isEqualTo(260);
    assertThat(node.get("datasetSha256").asText()).hasSize(64);
  }

  @Test
  void reportsInvalidFieldsWithoutCopyingRecordContents() {
    var input =
        new JobRequest.Input(
            null,
            null,
            List.of(
                new JobRequest.DataRecord("A", "a@example.test", 21),
                new JobRequest.DataRecord(" ", "bad", -1)));
    var node =
        json.readTree(
            processor.execute(
                claim(new JobRequest(JobRequest.Type.RECORD_VALIDATION, input, 0)), () -> false));
    assertThat(node.get("totalRecords").asInt()).isEqualTo(2);
    assertThat(node.get("validRecords").asInt()).isEqualTo(1);
    assertThat(node.get("errors").get(0).get("record").asInt()).isEqualTo(2);
    assertThat(node.get("errors").get(0).get("invalidFields").size()).isEqualTo(3);
    assertThat(node.toString()).doesNotContain("example.test", "bad");
  }

  @Test
  void stopsCooperativelyOnCancellationOrDeadline() {
    var c =
        claim(new JobRequest(JobRequest.Type.CSV_SUMMARY, new JobRequest.Input(1000, 1, null), 0));
    assertThatThrownBy(() -> processor.execute(c, () -> true))
        .isInstanceOf(JobProcessor.ProcessingFailure.class)
        .hasMessage("CANCELLED");
    var late = new JobProcessor(json, Clock.fixed(now.plusSeconds(6), ZoneOffset.UTC));
    assertThatThrownBy(() -> late.execute(c, () -> false)).hasMessage("EXECUTION_TIMEOUT");
  }

  @Test
  void workerRecordsSafeFailureWithoutLeakingExceptionText() {
    QueueStore store = mock(QueueStore.class);
    JobProcessor processing = mock(JobProcessor.class);
    var c = claim(new JobRequest(JobRequest.Type.CSV_SUMMARY, new JobRequest.Input(1, 1, null), 0));
    when(store.claim()).thenReturn(Optional.of(c), Optional.empty());
    when(processing.execute(eq(c), any()))
        .thenThrow(new IllegalStateException("sensitive payload"));
    Worker worker = new Worker(store, processing);
    worker.poll();
    worker.shutdown(); // joins worker tasks, no arbitrary sleep
    verify(store, atLeastOnce()).complete(c, null, "PROCESSING_ERROR", true);
  }
}
