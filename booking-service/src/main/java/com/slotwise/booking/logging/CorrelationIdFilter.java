package com.slotwise.booking.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads an inbound {@code X-Correlation-Id} header (or mints one) and puts it in the SLF4J
 * MDC for the lifetime of the request, so every log line written while handling it can be
 * grepped together — and echoes it back so the caller can do the same on their end.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Correlation-Id";
    private static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        final String correlationId = resolveCorrelationId(request);
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // ponytail: MDC is thread-local and threads are pooled — leaving a stale value
            // behind would leak this request's correlation ID into whatever the next request
            // handled by the same thread logs.
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolveCorrelationId(HttpServletRequest request) {
        final String existing = request.getHeader(HEADER);
        return existing != null && !existing.isBlank() ? existing : UUID.randomUUID().toString();
    }
}
