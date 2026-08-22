package com.atompay.cardpaycore.filter;

import com.atompay.cardpaycore.dto.ErrorResponse;
import com.atompay.cardpaycore.security.RateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String PROTECTED_PATH_PREFIX = "/api/v1/payments/";

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(@Value("${ratelimit.payments.limit:30}") int limit,
                           @Value("${ratelimit.payments.window-seconds:10}") int windowSeconds,
                           ObjectMapper objectMapper) {
        this.rateLimiter = new RateLimiter(limit, windowSeconds * 1000L);
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith(PROTECTED_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String actor = resolveActor();
        if (rateLimiter.tryAcquire(actor)) {
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = TimeUnit.MILLISECONDS.toSeconds(rateLimiter.millisUntilReset(actor)) + 1;
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(new ErrorResponse(
                OffsetDateTime.now(),
                HttpStatus.TOO_MANY_REQUESTS.value(),
                HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                "Rate limit exceeded. Retry after " + retryAfterSeconds + "s.",
                request.getRequestURI()
        )));
    }

    private String resolveActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return "anonymous";
        }
        return auth.getName();
    }
}
