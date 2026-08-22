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

    private static final String PAYMENTS_PATH_PREFIX = "/api/v1/payments/";
    private static final String LOGIN_PATH = "/api/v1/auth/login";

    private final RateLimiter paymentsLimiter;
    private final RateLimiter loginLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(@Value("${ratelimit.payments.limit:30}") int paymentsLimit,
                           @Value("${ratelimit.payments.window-seconds:10}") int paymentsWindowSeconds,
                           @Value("${ratelimit.login.limit:10}") int loginLimit,
                           @Value("${ratelimit.login.window-seconds:60}") int loginWindowSeconds,
                           ObjectMapper objectMapper) {
        this.paymentsLimiter = new RateLimiter(paymentsLimit, paymentsWindowSeconds * 1000L);
        this.loginLimiter = new RateLimiter(loginLimit, loginWindowSeconds * 1000L);
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        RateLimiter limiter;
        String key;

        if (uri.startsWith(PAYMENTS_PATH_PREFIX)) {
            limiter = paymentsLimiter;
            key = resolveActor();
        } else if (uri.equals(LOGIN_PATH)) {
            // No authenticated identity exists yet at login, so this keys on
            // remote IP -- not attacker-proof behind a shared NAT/proxy, but a
            // meaningful floor against a single-source brute-force loop.
            limiter = loginLimiter;
            key = request.getRemoteAddr();
        } else {
            filterChain.doFilter(request, response);
            return;
        }

        if (limiter.tryAcquire(key)) {
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = TimeUnit.MILLISECONDS.toSeconds(limiter.millisUntilReset(key)) + 1;
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(new ErrorResponse(
                OffsetDateTime.now(),
                HttpStatus.TOO_MANY_REQUESTS.value(),
                HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                "Rate limit exceeded. Retry after " + retryAfterSeconds + "s.",
                uri
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
