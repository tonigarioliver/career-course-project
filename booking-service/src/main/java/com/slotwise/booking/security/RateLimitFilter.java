package com.slotwise.booking.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

// Fase 3 "Rate Limiting": Redisson's RRateLimiter IS a token bucket (fixed capacity,
// refilled at a constant rate) — no need to hand-roll one. Sliding Window log was the other
// pattern on the roadmap; skipped, it needs a sorted-set-of-timestamps scheme this project
// doesn't need two rate-limiting algorithms doing the same job.
//
// One bucket per authenticated user (JWT subject), falling back to remote IP for anonymous
// requests. Runs after Spring Security's JWT filter so getName() already reflects the token,
// not before it.
//
// Deliberately NOT a @Component: Spring Boot auto-registers every Filter *bean* as a plain
// servlet filter too, outside the security chain — which would run this twice per request
// (double-charging the rate limiter). Not being a bean at all sidesteps that instead of
// registering it and then telling Boot not to (see SecurityConfig, which `new`s this).
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RedissonClient redissonClient;
    private final RateLimitProperties properties;

    @Override
    protected void doFilterInternal(
            final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
            throws ServletException, IOException {
        final var auth = SecurityContextHolder.getContext().getAuthentication();
        final var key = auth != null && auth.isAuthenticated() ? auth.getName() : request.getRemoteAddr();
        final var limiter = this.redissonClient.getRateLimiter("ratelimit:" + key);
        limiter.trySetRate(RateType.OVERALL, this.properties.permitsPerMinute(), Duration.ofMinutes(1));
        if (limiter.tryAcquire()) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Rate limit exceeded\"}");
        }
    }
}
