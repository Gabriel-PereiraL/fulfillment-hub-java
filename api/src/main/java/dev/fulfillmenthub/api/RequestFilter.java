package dev.fulfillmenthub.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestFilter extends OncePerRequestFilter {
    private record Budget(long minute, int count) {}
    private final ConcurrentHashMap<String, Budget> budgets = new ConcurrentHashMap<>();
    private final Clock clock;
    public RequestFilter(Clock clock) { this.clock = clock; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        if (request.getRequestURI().startsWith("/api/")) response.setHeader("Cache-Control", "no-store");
        var correlation = request.getHeader("X-Correlation-Id");
        if (correlation == null || !correlation.matches("[A-Za-z0-9_-]{1,64}")) correlation = UUID.randomUUID().toString();
        response.setHeader("X-Correlation-Id", correlation);
        var previousCorrelation = MDC.get("correlation_id");
        MDC.put("correlation_id", correlation);
        try {
            var path = request.getRequestURI();
            int limit = path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/change-password") ? 5
                    : path.startsWith("/api/v1/auth/") ? 30 : 0;
            if (limit > 0) {
                long minute = clock.instant().getEpochSecond() / 60;
                var bucket = budgets.compute(limit + ":" + request.getRemoteAddr(), (key, prior) ->
                        new Budget(minute, prior == null || prior.minute != minute ? 1 : prior.count + 1));
                if (budgets.size() > 1000) budgets.entrySet().removeIf(entry -> entry.getValue().minute < minute);
                if (bucket.count > limit) { HttpProblem.write(response, 429, "Too many requests"); return; }
            }
            int maximum = path.startsWith("/api/v1/webhooks/") ? 65536 : 262144;
            if (request.getContentLengthLong() > maximum) { HttpProblem.write(response, 413, "Payload too large"); return; }
            var bytes = request.getInputStream().readNBytes(maximum + 1);
            if (bytes.length > maximum) { HttpProblem.write(response, 413, "Payload too large"); return; }
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    var input = new ByteArrayInputStream(bytes);
                    return new ServletInputStream() {
                        @Override public int read() { return input.read(); }
                        @Override public boolean isFinished() { return input.available() == 0; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous request body"); }
                    };
                }
                @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); }
            }, response);
        } finally {
            if (previousCorrelation == null) MDC.remove("correlation_id");
            else MDC.put("correlation_id", previousCorrelation);
        }
    }
}
