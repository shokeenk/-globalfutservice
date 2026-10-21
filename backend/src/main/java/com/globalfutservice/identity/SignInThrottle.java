package com.globalfutservice.identity;

import java.time.Clock;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.security.AttemptThrottle;
import com.globalfutservice.web.ApiExceptions;
import org.springframework.stereotype.Component;

/**
 * Slows down repeated failed sign-ins, and repeated reset requests, per email address and
 * per IP.
 *
 * <p>Sits beside the account lock in {@link AuthService} rather than replacing it. The lock
 * only exists for an address with an account behind it; this counts every address typed,
 * so the answer is the same "too many attempts" whether the account exists or not. And the
 * per-IP count catches the other shape of attack -- one password tried against many
 * addresses -- which a per-account lock never sees.
 *
 * <p>The message never says which limit was reached or which part of the sign-in was wrong.
 */
@Component
public class SignInThrottle {

    static final String TOO_MANY = "Too many attempts. Please wait a few minutes and try again.";

    private final AttemptThrottle failuresByEmail;
    private final AttemptThrottle failuresByIp;
    private final AttemptThrottle resetsByEmail;
    private final AttemptThrottle resetsByIp;
    private final Clock clock;

    public SignInThrottle(AppProperties props, Clock clock) {
        AppProperties.Security s = props.security();
        this.failuresByEmail = new AttemptThrottle(s.loginFailuresPerEmail(), s.loginFailureWindow());
        this.failuresByIp = new AttemptThrottle(s.loginFailuresPerIp(), s.loginFailureWindow());
        this.resetsByEmail = new AttemptThrottle(s.resetRequestsPerEmail(), s.resetRequestWindow());
        this.resetsByIp = new AttemptThrottle(s.resetRequestsPerIp(), s.resetRequestWindow());
        this.clock = clock;
    }

    /** Before checking a password: refuses when this address or this IP has failed too often. */
    public void checkSignIn(String emailNormalised, String ip) {
        if (failuresByEmail.exhausted(emailNormalised) || failuresByIp.exhausted(ip)) {
            throw tooMany(failuresByEmail);
        }
    }

    /** A wrong password, or no such account: counted the same. */
    public void recordFailure(String emailNormalised, String ip) {
        failuresByEmail.record(emailNormalised);
        failuresByIp.record(ip);
    }

    /** Spends one reset request, or refuses when this address or this IP has asked too often. */
    public void spendResetRequest(String emailNormalised, String ip) {
        if (resetsByEmail.exhausted(emailNormalised) || resetsByIp.exhausted(ip)) {
            throw tooMany(resetsByEmail);
        }
        resetsByEmail.record(emailNormalised);
        resetsByIp.record(ip);
    }

    private ApiExceptions.TooManyRequestsException tooMany(AttemptThrottle throttle) {
        return new ApiExceptions.TooManyRequestsException("too_many_attempts", TOO_MANY,
                throttle.retryAt(clock.instant()));
    }
}
