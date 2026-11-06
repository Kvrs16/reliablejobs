package dev.reliablejobs.web;

import jakarta.servlet.http.*;
import java.io.IOException;
import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrors {
  private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> domain(ApiException e, HttpServletRequest req) {
    return problem(e.status, e.code, e.getMessage(), req);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class
  })
  ResponseEntity<ProblemDetail> invalid(Exception e, HttpServletRequest req) {
    return problem(
        400,
        "INVALID_REQUEST",
        "Request failed validation; check field types, ranges, and supported fields",
        req);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ProblemDetail> method(Exception e, HttpServletRequest req) {
    return problem(405, "METHOD_NOT_ALLOWED", "Method not supported", req);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ProblemDetail> media(Exception e, HttpServletRequest req) {
    return problem(415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json for job submissions", req);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ProblemDetail> missing(Exception e, HttpServletRequest req) {
    return problem(404, "NOT_FOUND", "Resource not found", req);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest req) {
    log.atError().addKeyValue("errorType", e.getClass().getSimpleName()).log("Request failed");
    return problem(500, "INTERNAL_ERROR", "Request could not be completed", req);
  }

  private static ResponseEntity<ProblemDetail> problem(
      int status, String code, String detail, HttpServletRequest req) {
    ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
    p.setProperty("code", code);
    p.setProperty("correlationId", req.getAttribute("correlationId"));
    return ResponseEntity.status(status).body(p);
  }

  public static void write(
      HttpServletRequest req, HttpServletResponse res, int status, String code, String detail)
      throws IOException {
    res.setStatus(status);
    res.setContentType("application/problem+json");
    // All values below are application constants or a validated correlation ID.
    res.getWriter()
        .write(
            "{\"type\":\"about:blank\",\"status\":"
                + status
                + ",\"code\":\""
                + code
                + "\",\"detail\":\""
                + detail
                + "\",\"correlationId\":\""
                + req.getAttribute("correlationId")
                + "\"}");
  }
}
