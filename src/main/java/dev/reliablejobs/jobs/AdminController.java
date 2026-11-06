package dev.reliablejobs.jobs;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/jobs")
public class AdminController {
  private final JobService service;

  public AdminController(JobService service) {
    this.service = service;
  }

  @GetMapping
  JobDtos.Listing list(
      @RequestParam(required = false) JobStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.list(null, status, page, size, true);
  }

  @GetMapping("/{id}")
  JobDtos.View get(@PathVariable UUID id) {
    return service.adminGet(id);
  }

  @GetMapping("/{id}/attempts")
  List<JobDtos.Attempt> attempts(@PathVariable UUID id) {
    return service.adminAttempts(id);
  }

  @GetMapping("/{id}/audit")
  List<JobDtos.Audit> audit(@PathVariable UUID id) {
    return service.audit(id);
  }
}
