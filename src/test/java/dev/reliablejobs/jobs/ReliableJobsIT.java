package dev.reliablejobs.jobs;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.reliablejobs.auth.*;
import dev.reliablejobs.web.ApiException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.system.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"reliablejobs.worker.enabled=false", "logging.level.root=WARN"})
@AutoConfigureMockMvc
@Import(ReliableJobsIT.TimeConfig.class)
class ReliableJobsIT {
  static final EmbeddedPostgres PG = startPostgres();
  static final String PASSWORD = UUID.randomUUID().toString();
  static String passwordHash;

  static EmbeddedPostgres startPostgres() {
    try {
      return EmbeddedPostgres.builder()
          .setPort(0)
          .setServerConfig("listen_addresses", "127.0.0.1")
          .start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
    r.add("spring.datasource.username", () -> "postgres");
    r.add("spring.datasource.password", () -> "");
  }

  @TestConfiguration
  static class TimeConfig {
    @Bean
    @Primary
    TestClock testClock() {
      return new TestClock();
    }
  }

  static class TestClock extends Clock {
    final AtomicReference<Instant> now = new AtomicReference<>();

    void set(Instant t) {
      now.set(t);
    }

    void advance(Duration duration) {
      now.updateAndGet(t -> t.plus(duration));
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now.get();
    }
  }

  @Autowired MockMvc mvc;
  @Autowired JobService service;
  @Autowired QueueStore queue;
  @Autowired JobProcessor processor;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository users;
  @Autowired PasswordEncoder encoder;
  @Autowired JsonMapper json;
  @Autowired TestClock clock;
  @Autowired JobRepository jobs;
  @Autowired PlatformTransactionManager transactions;

  @Value("${local.server.port}")
  int port;

  @BeforeEach
  void reset() {
    jdbc.update("delete from audit_events");
    jdbc.update("delete from execution_attempts");
    jdbc.update("delete from jobs");
    users.deleteAll();
    clock.set(Instant.parse("2026-01-01T00:00:00Z"));
    if (passwordHash == null) passwordHash = encoder.encode(PASSWORD);
    users.saveAllAndFlush(
        List.of(
            new AppUser("alice", passwordHash, "USER"),
            new AppUser("bob", passwordHash, "USER"),
            new AppUser("admin", passwordHash, "ADMIN")));
  }

  @AfterAll
  static void closeDatabase() throws Exception {
    PG.close();
  }

  JobRequest csv(int retries) {
    return new JobRequest(JobRequest.Type.CSV_SUMMARY, new JobRequest.Input(20, 42, null), retries);
  }

  @Test
  void passwordResetPreservesOwnerAndJobsAndChangesLogin() throws Exception {
    UUID jobId = submit(0);
    AppUser before = users.findByUsername("alice").orElseThrow();
    String replacement = UUID.randomUUID().toString();
    String hash = encoder.encode(replacement);
    assertThat(users.resetPasswordHash("alice", hash)).isEqualTo(1);
    AppUser after = users.findByUsername("alice").orElseThrow();
    assertThat(after.getId()).isEqualTo(before.getId());
    assertThat(after.getRole()).isEqualTo(before.getRole());
    assertThat(encoder.matches(replacement, after.getPasswordHash())).isTrue();
    assertThat(service.get(jobId, "alice").id()).isEqualTo(jobId);
    mvc.perform(
            post("/api/auth/login")
                .with(csrf())
                .param("username", "alice")
                .param("password", PASSWORD))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/auth/login")
                .with(csrf())
                .param("username", "alice")
                .param("password", replacement))
        .andExpect(status().isNoContent());
    assertThat(users.resetPasswordHash("missing-user", hash)).isZero();
  }

  UUID submit(int retries) {
    return service.submit("alice", UUID.randomUUID().toString(), csv(retries)).job().id();
  }

  JobDtos.View view(UUID id) {
    return service.get(id, "alice");
  }

  JobDtos.Claim claim() {
    return queue.claim().orElseThrow();
  }

  void succeed(JobDtos.Claim c) {
    assertThat(queue.complete(c, processor.execute(c, () -> false), null, false)).isTrue();
  }

  @Test
  void migrationFromEmptyPostgresqlAndSchemaConstraints() {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select version()", String.class)).contains("PostgreSQL 18.6");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_indexes where tablename='jobs'", Integer.class))
        .isGreaterThanOrEqualTo(6);
    UUID id = submit(0);
    assertThatThrownBy(() -> jdbc.update("update jobs set status='RUNNING' where id=?", id))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void sessionLoginCsrfLogoutAndBadCredentials() throws Exception {
    mvc.perform(get("/api/jobs"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mvc.perform(post("/api/auth/login").param("username", "alice").param("password", PASSWORD))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/auth/login")
                .with(csrf())
                .param("username", "alice")
                .param("password", "wrong"))
        .andExpect(status().isUnauthorized());
    MockHttpSession session = new MockHttpSession();
    String csrf =
        json.readTree(
                mvc.perform(get("/api/auth/csrf").session(session))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("token")
            .asText();
    MvcResult login =
        mvc.perform(
                post("/api/auth/login")
                    .session(session)
                    .header("X-CSRF-TOKEN", csrf)
                    .param("username", "alice")
                    .param("password", PASSWORD))
            .andExpect(status().isNoContent())
            .andReturn();
    MockHttpSession authenticated = (MockHttpSession) login.getRequest().getSession(false);
    mvc.perform(get("/api/jobs").session(authenticated)).andExpect(status().isOk());
    mvc.perform(post("/api/auth/logout").session(authenticated).with(csrf()))
        .andExpect(status().isNoContent());
    assertThat(authenticated.isInvalid()).isTrue();
  }

  @Test
  void csrfRequiredForSubmissionAndSameOriginHasNoCorsGrant() throws Exception {
    mvc.perform(
            post("/api/jobs")
                .with(user("alice"))
                .contentType("application/json")
                .content(json.writeValueAsString(csv(0))))
        .andExpect(status().isForbidden());
    mvc.perform(
            options("/api/jobs")
                .header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  @Test
  void ownershipChecksCoverEveryOperationAndAdminVisibility() throws Exception {
    UUID id = submit(1);
    succeed(claim());
    for (String suffix : List.of("", "/attempts", "/result")) {
      mvc.perform(get("/api/jobs/" + id + suffix).with(user("bob")))
          .andExpect(status().isNotFound());
    }
    for (String op : List.of("cancel", "retry")) {
      mvc.perform(post("/api/jobs/" + id + "/" + op).with(user("bob")).with(csrf()))
          .andExpect(status().isNotFound());
    }
    mvc.perform(get("/api/jobs").with(user("bob"))).andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get("/api/jobs/" + id + "/result").with(user("alice")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows").value(20));
    mvc.perform(get("/api/admin/jobs").with(user("alice"))).andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/jobs").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(get("/api/admin/jobs/" + id + "/attempts").with(user("admin").roles("ADMIN")))
        .andExpect(jsonPath("$[0].outcome").value("SUCCEEDED"));
    mvc.perform(get("/api/admin/jobs/" + id + "/audit").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/jobs/" + id + "/result").with(user("admin").roles("ADMIN")))
        .andExpect(status().isNotFound());
  }

  @Test
  void validationPayloadBoundsUnknownFieldsAndPaging() throws Exception {
    for (String body :
        List.of(
            "{}",
            "{\"type\":\"CSV_SUMMARY\",\"input\":{\"rows\":1001,\"seed\":42}}",
            "{\"type\":\"CSV_SUMMARY\",\"input\":{\"rows\":2}}",
            "{\"type\":\"RECORD_VALIDATION\",\"input\":{\"records\":[null]}}",
            "{\"type\":\"CSV_SUMMARY\",\"input\":{\"rows\":2,\"seed\":42},\"retryLimit\":4}",
            "{\"type\":\"CSV_SUMMARY\",\"input\":{\"rows\":2,\"seed\":42,\"script\":\"no\"}}")) {
      mvc.perform(
              post("/api/jobs")
                  .with(user("alice"))
                  .with(csrf())
                  .header("Idempotency-Key", "key")
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            post("/api/jobs")
                .with(user("alice"))
                .with(csrf())
                .header("Idempotency-Key", "large")
                .contentType("application/json")
                .content(" ".repeat(65537)))
        .andExpect(status().isPayloadTooLarge());
    mvc.perform(get("/api/jobs?size=101").with(user("alice"))).andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/jobs")
                .with(user("alice"))
                .with(csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(csv(0))))
        .andExpect(status().isBadRequest());
    assertThat(jobs.count()).isZero();
  }

  @Test
  void idempotentReplayFingerprintConflictAndUserScoping() throws Exception {
    String body = json.writeValueAsString(csv(1));
    mvc.perform(
            post("/api/jobs")
                .with(user("alice"))
                .with(csrf())
                .header("Idempotency-Key", "same")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "false"));
    mvc.perform(
            post("/api/jobs")
                .with(user("alice"))
                .with(csrf())
                .header("Idempotency-Key", "same")
                .contentType("application/json")
                .content(
                    "{ \"retryLimit\": 1, \"input\": {\"seed\":42,\"rows\":20},"
                        + " \"type\":\"CSV_SUMMARY\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"));
    mvc.perform(
            post("/api/jobs")
                .with(user("alice"))
                .with(csrf())
                .header("Idempotency-Key", "same")
                .contentType("application/json")
                .content(json.writeValueAsString(csv(0))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    assertThat(service.submit("bob", "same", csv(1)).replayed()).isFalse();
    assertThat(jobs.count()).isEqualTo(2);
  }

  @Test
  void concurrentDuplicateSubmissionReturnsOneJob() throws Exception {
    var results =
        race(
            () -> service.submit("alice", "concurrent", csv(0)),
            () -> service.submit("alice", "concurrent", csv(0)));
    assertThat(results.get(0).job().id()).isEqualTo(results.get(1).job().id());
    assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
    assertThat(jobs.count()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from audit_events", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void concurrentConflictingSubmissionsHaveOneWinner() throws Exception {
    var results = race(() -> trySubmit(csv(0)), () -> trySubmit(csv(1)));
    assertThat(results).containsExactlyInAnyOrder(201, 409);
    assertThat(jobs.count()).isEqualTo(1);
  }

  int trySubmit(JobRequest request) {
    try {
      service.submit("alice", "collision", request);
      return 201;
    } catch (ApiException e) {
      return e.status;
    }
  }

  @Test
  void twoWorkersCompeteForOneEligibleJob() throws Exception {
    submit(0);
    var results = race(queue::claim, queue::claim);
    assertThat(results.stream().filter(Optional::isPresent).count()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from execution_attempts", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void skipLockedDoesNotWaitForAnotherWorkersRowLock() throws Exception {
    UUID id = submit(0);
    CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<?> lock =
          pool.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .execute(
                          s -> {
                            assertThat(jobs.claimable(clock.instant())).isPresent();
                            locked.countDown();
                            await(release);
                            return null;
                          }));
      assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(queue.claim()).isEmpty();
      release.countDown();
      lock.get(10, TimeUnit.SECONDS);
      assertThat(claim().jobId()).isEqualTo(id);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void retryBackoffAndExhaustionAreBounded() {
    UUID id = submit(2);
    for (int attempt = 1; attempt <= 3; attempt++) {
      var c = claim();
      assertThat(view(id).attemptCount()).isEqualTo(attempt);
      queue.complete(c, null, "PROCESSING_ERROR", true);
      if (attempt < 3) {
        Duration wait = Duration.ofSeconds(attempt == 1 ? 5 : 10);
        assertThat(view(id).nextExecutionAt()).isEqualTo(clock.instant().plus(wait));
        assertThat(queue.claim()).isEmpty();
        clock.advance(wait.minusMillis(1));
        assertThat(queue.claim()).isEmpty();
        clock.advance(Duration.ofMillis(1));
      }
    }
    assertThat(view(id).status()).isEqualTo(JobStatus.FAILED);
    assertThat(service.attempts(id, "alice")).hasSize(3);
    assertThatThrownBy(() -> service.retry(id, "alice"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("budget");
  }

  @Test
  void abandonedExecutionIsRecoveredAndStaleCompletionRejected() {
    UUID id = submit(1);
    var first = claim();
    String computed =
        processor.execute(first, () -> false); // crash after computation, before durable completion
    clock.advance(QueueStore.LEASE);
    assertThat(queue.recover()).isEqualTo(1);
    assertThat(queue.complete(first, computed, null, false)).isFalse();
    assertThat(view(id).status()).isEqualTo(JobStatus.RETRY_PENDING);
    clock.advance(Duration.ofSeconds(5));
    var second = claim();
    assertThat(processor.execute(second, () -> false)).isEqualTo(computed);
    succeed(second);
    assertThat(view(id).status()).isEqualTo(JobStatus.SUCCEEDED);
    assertThat(service.attempts(id, "alice"))
        .extracting(JobDtos.Attempt::outcome)
        .containsExactly("ABANDONED", "SUCCEEDED");
    assertThat(queue.recover()).isZero();
  }

  @Test
  void abandonedFinalAttemptFailsAndExpiredWorkerCannotComplete() {
    UUID id = submit(0);
    var c = claim();
    clock.advance(QueueStore.LEASE);
    assertThat(queue.complete(c, "{}", null, false)).isFalse();
    assertThat(queue.recover()).isEqualTo(1);
    assertThat(view(id).status()).isEqualTo(JobStatus.FAILED);
    assertThat(view(id).failureCode()).isEqualTo("LEASE_EXPIRED");
  }

  @Test
  void cancellationPermittedForPendingRetryPendingRunningAndRepeat() {
    UUID pending = submit(0);
    assertThat(service.cancel(pending, "alice").status()).isEqualTo(JobStatus.CANCELLED);
    assertThat(service.cancel(pending, "alice").status()).isEqualTo(JobStatus.CANCELLED);
    UUID retry = submit(1);
    queue.complete(claim(), null, "PROCESSING_ERROR", true);
    assertThat(service.cancel(retry, "alice").status()).isEqualTo(JobStatus.CANCELLED);
    UUID running = submit(1);
    var c = claim();
    assertThat(service.cancel(running, "alice").status()).isEqualTo(JobStatus.RUNNING);
    assertThat(queue.shouldStop(c)).isTrue();
    assertThat(queue.complete(c, "{}", null, false)).isTrue();
    assertThat(view(running).status()).isEqualTo(JobStatus.CANCELLED);
    assertThatThrownBy(() -> service.result(running, "alice")).isInstanceOf(ApiException.class);
    assertThat(queue.claim()).isEmpty();
  }

  @Test
  void recoveryHonorsCancellationAndTerminalJobsRejectCancellation() {
    UUID cancelled = submit(1);
    claim();
    service.cancel(cancelled, "alice");
    clock.advance(QueueStore.LEASE);
    queue.recover();
    assertThat(view(cancelled).status()).isEqualTo(JobStatus.CANCELLED);
    UUID successful = submit(0);
    succeed(claim());
    assertThatThrownBy(() -> service.cancel(successful, "alice")).isInstanceOf(ApiException.class);
    UUID failed = submit(0);
    queue.complete(claim(), null, "PROCESSING_ERROR", true);
    assertThatThrownBy(() -> service.cancel(failed, "alice")).isInstanceOf(ApiException.class);
  }

  @Test
  void manualRetryUsesRemainingBudgetAndCannotEditStatuses() throws Exception {
    UUID id = submit(1);
    queue.complete(claim(), null, "RESULT_TOO_LARGE", false);
    mvc.perform(post("/api/jobs/" + id + "/retry").with(user("alice")).with(csrf()))
        .andExpect(status().isAccepted());
    assertThat(queue.claim()).isEmpty();
    clock.advance(Duration.ofSeconds(5));
    succeed(claim());
    mvc.perform(post("/api/jobs/" + id + "/retry").with(user("alice")).with(csrf()))
        .andExpect(status().isConflict());
    mvc.perform(
            patch("/api/jobs/" + id)
                .with(user("alice"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"status\":\"PENDING\"}"))
        .andExpect(status().isMethodNotAllowed());
  }

  @Test
  void deadlinesAndResultSizeAreEnforced() {
    UUID timeout = submit(0);
    var c = claim();
    clock.advance(QueueStore.EXECUTION_LIMIT);
    queue.complete(c, "{}", null, false);
    assertThat(view(timeout).failureCode()).isEqualTo("EXECUTION_TIMEOUT");
    UUID large = submit(0);
    queue.complete(claim(), "x".repeat(65537), null, false);
    assertThat(view(large).failureCode()).isEqualTo("RESULT_TOO_LARGE");
    assertThatThrownBy(() -> service.result(large, "alice")).isInstanceOf(ApiException.class);
  }

  @Test
  void filteringPaginationCorrelationHealthAndManagementSecurity() throws Exception {
    UUID first = submit(0);
    service.cancel(first, "alice");
    submit(0);
    mvc.perform(
            get("/api/jobs?status=PENDING&size=1")
                .with(user("alice"))
                .header("X-Correlation-ID", "demo_123"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-ID", "demo_123"))
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components").doesNotExist());
    mvc.perform(get("/actuator/metrics").with(user("alice"))).andExpect(status().isForbidden());
    mvc.perform(get("/actuator/metrics/reliablejobs.submitted").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk());
  }

  @Test
  void openApiGeneratesForAuthenticatedUsers() throws Exception {
    mvc.perform(get("/v3/api-docs").with(user("alice")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/jobs']").exists());
  }

  @Test
  void realHttpSessionSubmissionExecutorAndResult() throws Exception {
    String base = "http://127.0.0.1:" + port;
    var client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    var initial =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String token = json.readTree(initial.body()).get("token").asText();
    assertThat(initial.headers().allValues("set-cookie").toString())
        .contains("HttpOnly", "SameSite=Strict");
    var login =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-CSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString("username=alice&password=" + PASSWORD))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(login.statusCode()).isEqualTo(204);
    var csrf =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    token = json.readTree(csrf.body()).get("token").asText();
    var submitted =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/jobs"))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", token)
                .header("Idempotency-Key", "http-smoke")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(csv(0))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(submitted.statusCode()).isEqualTo(201);
    String id = json.readTree(submitted.body()).get("id").asText();
    Worker worker = new Worker(queue, processor);
    worker.poll();
    worker.shutdown();
    var result =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/jobs/" + id + "/result")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(result.statusCode()).isEqualTo(200);
    assertThat(json.readTree(result.body()).get("rows").asInt()).isEqualTo(20);
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void payloadValuesAreNotLoggedDuringSubmission(CapturedOutput output) throws Exception {
    var request =
        new JobRequest(
            JobRequest.Type.RECORD_VALIDATION,
            new JobRequest.Input(
                null,
                null,
                List.of(
                    new JobRequest.DataRecord(
                        "synthetic-private-name", "private@example.test", 21))),
            0);
    mvc.perform(
            post("/api/jobs")
                .with(user("alice"))
                .with(csrf())
                .header("Idempotency-Key", "redacted")
                .contentType("application/json")
                .content(json.writeValueAsString(request)))
        .andExpect(status().isCreated());
    assertThat(output.getAll()).doesNotContain("synthetic-private-name", "private@example.test");
  }

  static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Barrier timed out");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  static <T> List<T> race(Callable<T> a, Callable<T> b) throws Exception {
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<T> first =
          pool.submit(
              () -> {
                start.await(10, TimeUnit.SECONDS);
                return a.call();
              });
      Future<T> second =
          pool.submit(
              () -> {
                start.await(10, TimeUnit.SECONDS);
                return b.call();
              });
      return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }
}
