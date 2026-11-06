package dev.reliablejobs.jobs;

import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
  private final JobService service;

  public JobController(JobService service) {
    this.service = service;
  }

  @PostMapping
  ResponseEntity<JobDtos.View> submit(
      Principal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody JobRequest request) {
    var submission = service.submit(principal.getName(), key, request);
    return ResponseEntity.status(submission.replayed() ? 200 : 201)
        .location(URI.create("/api/jobs/" + submission.job().id()))
        .header("Idempotency-Replayed", Boolean.toString(submission.replayed()))
        .body(submission.job());
  }

  @GetMapping
  JobDtos.Listing list(
      Principal p,
      @RequestParam(required = false) JobStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.list(p.getName(), status, page, size, false);
  }

  @GetMapping("/{id}")
  JobDtos.View get(Principal p, @PathVariable UUID id) {
    return service.get(id, p.getName());
  }

  @GetMapping("/{id}/attempts")
  List<JobDtos.Attempt> attempts(Principal p, @PathVariable UUID id) {
    return service.attempts(id, p.getName());
  }

  @GetMapping("/{id}/result")
  Object result(Principal p, @PathVariable UUID id) {
    return service.result(id, p.getName());
  }

  @PostMapping("/{id}/cancel")
  ResponseEntity<JobDtos.View> cancel(Principal p, @PathVariable UUID id) {
    var view = service.cancel(id, p.getName());
    return ResponseEntity.status(view.status() == JobStatus.RUNNING ? 202 : 200).body(view);
  }

  @PostMapping("/{id}/retry")
  ResponseEntity<JobDtos.View> retry(Principal p, @PathVariable UUID id) {
    return ResponseEntity.accepted().body(service.retry(id, p.getName()));
  }
}
