package com.globalfutservice.security;

import com.globalfutservice.config.AppProperties;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-caller request budgets, applied before anything expensive happens.
 *
 * <p>Three buckets, because the three surfaces have different attackers:
 * <ul>
 *   <li><b>Quotes</b> — competitors price-match by scraping. A configurator that answers
 *       thousands of times a minute is a public price feed.</li>
 *   <li><b>Auth</b> — credential stuffing. Per-IP limiting here is what turns a list of
 *       breached passwords from an afternoon's work into a month's.</li>
 *   <li><b>Order creation</b> — payment-gateway abuse and card testing.</li>
 * </ul>
 *
 * <p><b>Deployment note.</b> The buckets are in-memory, which is correct for a single
 * instance and wrong the moment there are two: each replica would grant the full budget.
 * Behind more than one instance, swap the map for {@code bucket4j-redis} — the call sites
 * do not change.
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private final AppProperties props;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong lastSweep = new AtomicLong(System.currentTimeMillis());

    public RateLimitFilter(AppProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!props.rateLimit().enabled()) {
            return true;
        }
        String path = request.getRequestURI();
        // The gateway webhook must never be throttled: dropping it means an order sits
        // unpaid in the database while the customer's money has already moved. It is
        // protected by its HMAC signature instead, which is the stronger control.
        return path.startsWith("/api/v1/payments/webhook")
                || path.startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        sweepIfStale();

        String path = request.getRequestURI();
        int limit = limitFor(path);
        String key = limit + ":" + path.startsWith("/api/v1/admin") + ":" + clientKey(request);

        Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket(limit));
        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", "60");
        response.getWriter().write("""
                {"error":"rate_limited","message":"Too many requests. Please wait a minute and try again."}""");
    }

    private int limitFor(String path) {
        AppProperties.RateLimit rl = props.rateLimit();
        if (path.startsWith("/api/v1/quotes")) {
            return rl.quotesPerMinute();
        }
        if (path.startsWith("/api/v1/auth")) {
            return rl.authPerMinute();
        }
        if (path.equals("/api/v1/orders")) {
            return rl.orderCreationPerMinute();
        }
        return rl.defaultPerMinute();
    }

    /**
     * A bucket holding {@code perMinute} tokens that refills continuously over a
     * minute.
     *
     * <p>{@code refillGreedy} rather than {@code refillIntervally}: tokens trickle
     * back throughout the minute instead of arriving all at once on the boundary.
     * Interval refill lets a caller spend the whole budget at 11:59:59 and the whole
     * budget again at 12:00:00 — double the intended rate across one second, which
     * is exactly the burst a limiter is meant to stop.
     */
    private static Bucket newBucket(int perMinute) {
        return Bucket.builder()
                .addLimit(limit -> limit
                        .capacity(perMinute)
                        .refillGreedy(perMinute, Duration.ofMinutes(1)))
                .build();
    }

    /**
     * Prefer the authenticated account over the IP. Two flatmates behind one NAT should
     * not share a login budget, and a signed-in abuser should not escape one by changing
     * networks.
     */
    private static String clientKey(HttpServletRequest request) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AccountPrincipal principal) {
            return "acct:" + principal.id();
        }
        // getRemoteAddr is correct here because server.forward-headers-strategy=framework
        // makes Spring apply X-Forwarded-For from the trusted proxy for us. Reading the
        // header directly would let any caller spoof its own identity.
        return "ip:" + request.getRemoteAddr();
    }

    /** Cheap eviction so an IP-keyed map cannot grow without bound. */
    private void sweepIfStale() {
        long now = System.currentTimeMillis();
        long previous = lastSweep.get();
        if (now - previous > Duration.ofMinutes(10).toMillis()
                && lastSweep.compareAndSet(previous, now)) {
            buckets.clear();
        }
    }
}
