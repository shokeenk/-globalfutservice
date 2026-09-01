package com.globalfutservice.identity.web;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.identity.AuthService;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Accounts and sessions")
public class AuthController {

    static final String REFRESH_COOKIE = "gfs_refresh";

    private final AuthService authService;
    private final AccountRepository accounts;
    private final AppProperties props;

    public AuthController(AuthService authService, AccountRepository accounts, AppProperties props) {
        this.authService = authService;
        this.accounts = accounts;
        this.props = props;
    }

    @PostMapping("/register")
    @Operation(summary = "Create a customer account")
    public ResponseEntity<AuthDtos.TokenResponse> register(
            @Valid @RequestBody AuthDtos.RegisterRequest request, HttpServletRequest http) {
        AuthService.Session session = authService.register(
                request, http.getHeader(HttpHeaders.USER_AGENT), http.getRemoteAddr());
        return respond(session, HttpStatus.CREATED);
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in")
    public ResponseEntity<AuthDtos.TokenResponse> login(
            @Valid @RequestBody AuthDtos.LoginRequest request, HttpServletRequest http) {
        AuthService.Session session = authService.login(
                request, http.getHeader(HttpHeaders.USER_AGENT), http.getRemoteAddr());
        return respond(session, HttpStatus.OK);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange the refresh cookie for a new access token",
            description = "Rotates the refresh token. Presenting one that has already been "
                    + "used revokes the entire session family.")
    public ResponseEntity<AuthDtos.TokenResponse> refresh(
            @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest http) {
        AuthService.Session session = authService.refresh(
                refreshToken, http.getHeader(HttpHeaders.USER_AGENT), http.getRemoteAddr());
        return respond(session, HttpStatus.OK);
    }

    @PostMapping("/logout")
    @Operation(summary = "Sign out of this device")
    public ResponseEntity<Void> logout(
            @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredCookie().toString())
                .build();
    }

    @GetMapping("/me")
    @Operation(summary = "The signed-in account")
    public ResponseEntity<AuthDtos.AccountResponse> me(@CurrentAccount AccountPrincipal principal) {
        if (principal == null) {
            throw new ApiExceptions.ForbiddenException("Please sign in.");
        }
        AccountEntity account = accounts.findById(principal.id())
                .orElseThrow(() -> new ApiExceptions.ForbiddenException("Please sign in."));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(authService.describe(account));
    }

    private ResponseEntity<AuthDtos.TokenResponse> respond(AuthService.Session session,
                                                           HttpStatus status) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AuthDtos.TokenResponse(
                        session.accessToken(),
                        authService.accessTokenSeconds(),
                        authService.describe(session.account())));
    }

    /**
     * The long-lived half of the session.
     *
     * <p>{@code HttpOnly} keeps it out of reach of any script on the page, so an XSS bug
     * costs the current tab rather than a month of account access. {@code SameSite=Strict}
     * means the browser will not attach it to a request originating from another site,
     * which is what makes it safe to run this API without CSRF tokens. {@code Secure} is
     * tied to configuration so local development over plain HTTP still works, and the
     * path is scoped to the auth endpoints so it is not sent with every API call.
     */
    private ResponseCookie refreshCookie(String value) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(props.security().requireHttps())
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(props.security().refreshTokenTtl())
                .build();
    }

    private ResponseCookie expiredCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(props.security().requireHttps())
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(0)
                .build();
    }
}
