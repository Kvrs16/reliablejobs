package dev.reliablejobs.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestGuard extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(RequestGuard.class);

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String supplied = req.getHeader("X-Correlation-ID");
    String id =
        supplied != null && supplied.matches("[a-zA-Z0-9_-]{1,64}")
            ? supplied
            : UUID.randomUUID().toString();
    req.setAttribute("correlationId", id);
    res.setHeader("X-Correlation-ID", id);
    try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", id)) {
      if (req.getRequestURI().equals("/api/jobs") && req.getMethod().equals("POST")) {
        byte[] body = req.getInputStream().readNBytes(65537);
        if (body.length > 65536) {
          ApiErrors.write(
              req, res, 413, "PAYLOAD_TOO_LARGE", "Maximum request size is 65536 bytes");
          return;
        }
        req = new CachedRequest(req, body);
      }
      chain.doFilter(req, res);
      log.atInfo()
          .addKeyValue("method", req.getMethod())
          .addKeyValue("status", res.getStatus())
          .log("Request completed");
    }
  }

  private static class CachedRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    CachedRequest(HttpServletRequest req, byte[] body) {
      super(req);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      var input = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        public int read() {
          return input.read();
        }

        public boolean isFinished() {
          return input.available() == 0;
        }

        public boolean isReady() {
          return true;
        }

        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("Synchronous requests only");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(
          new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
    }
  }
}
