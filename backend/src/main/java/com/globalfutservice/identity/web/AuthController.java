package com.globalfutservice.identity.web;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.identity.AuthService;
import com.globalfutservice.identity.PasswordService;
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

    static final String PASSWORD_UPDATED = "Password updated, please sign in.";

    private final AuthService authService;
    private final PasswordService passwordService;
    private final AccountRepository accounts;
    private final AppProperties props;

    public AuthController(AuthService authService, PasswordService passwordService,
                          AccountRepository accounts, AppProperties props) {
        this.authService = authService;
        this.passwordService = passwordService;
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

    @PostMapping("/password/forgot")
    @Operation(summary = "Email a password reset link",
            description = "Answers the same whether or not the address has an account.")
    public ResponseEntity<AuthDtos.MessageResponse> forgotPassword(
            @Valid @RequestBody AuthDtos.ForgotPasswordRequest request, HttpServletRequest http) {
        passwordService.requestReset(request.email(), request.language(), request.humanToken(),
                http.getRemoteAddr());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AuthDtos.MessageResponse(PasswordService.FORGOT_REPLY));
    }

    @PostMapping("/password/reset")
    @Operation(summary = "Set a new password from a reset link",
            description = "Signs the account out everywhere, this browser included.")
    public ResponseEntity<AuthDtos.MessageResponse> resetPassword(
            @Valid @RequestBody AuthDtos.ResetPasswordRequest request) {
        passwordService.resetPassword(request.token(), request.password(), request.language());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredCookie().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AuthDtos.MessageResponse(PASSWORD_UPDATED));
    }

    /**
     * Under /auth rather than /account so the refresh cookie, scoped to this path, comes
     * with it: that cookie is how the device making the change keeps its session while
     * every other one is signed out.
     */
    @PostMapping("/password/change")
    @Operation(summary = "Change the signed-in account's password",
            description = "Signs out every other session.")
    public ResponseEntity<Void> changePassword(
            @CurrentAccount AccountPrincipal principal,
            @Valid @RequestBody AuthDtos.ChangePasswordRequest request,
            @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest http) {
        if (principal == null) {
            throw new ApiExceptions.ForbiddenException("Please sign in.");
        }
        passwordService.changePassword(principal.id(), request.currentPassword(), request.newPassword(),
                request.language(), refreshToken, http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password/set-link")
    @Operation(summary = "Email the signed-in account a link to set a password",
            description = "For accounts made with Google or Discord, which have none.")
    public ResponseEntity<Void> setPasswordLink(
            @CurrentAccount AccountPrincipal principal,
            @Valid @RequestBody(required = false) AuthDtos.SetPasswordLinkRequest request,
            HttpServletRequest http) {
        if (principal == null) {
            throw new ApiExceptions.ForbiddenException("Please sign in.");
        }
        passwordService.sendSetPasswordLink(principal.id(), request == null ? null : request.language(),
                http.getRemoteAddr());
        return ResponseEntity.noContent().build();
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
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session, props).toString())
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
     *
     * <p>"Remember me" decides only its lifetime. Ticked, it carries a Max-Age and outlives
     * the browser. Unticked, it has none: a session cookie, gone when the browser closes.
     * The password itself is never stored anywhere in the browser either way.
     */
    static ResponseCookie refreshCookie(AuthService.Session session, AppProperties props) {
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(REFRESH_COOKIE, session.refreshToken())
                .httpOnly(true)
                .secure(props.security().requireHttps())
                .sameSite("Strict")
                .path("/api/v1/auth");
        if (session.persistent()) {
            cookie.maxAge(props.security().refreshTokenTtl());
        }
        return cookie.build();
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
