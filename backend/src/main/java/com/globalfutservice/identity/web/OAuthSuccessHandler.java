package com.globalfutservice.identity.web;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AuthService;
import com.globalfutservice.identity.OAuthAccountService;
import com.globalfutservice.identity.OAuthAccountService.ProviderIdentity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * The end of the redirect: a provider identity becomes a session here.
 *
 * <p>Spring has authenticated the person against Google or Discord by the time this
 * runs. That authentication is then thrown away — the browser leaves holding the same
 * first-party refresh cookie the password flow issues, and every subsequent request is
 * checked exactly as a password sign-in would be. One session model, one place to
 * revoke, and no path where a provider token is accepted as a credential here.
 *
 * <p><b>No token travels in the URL.</b> The first version put the access token in the
 * fragment — safer than a query string, which lands in access logs and {@code Referer}
 * headers — but it turned out to be unnecessary: the redirect is a full page load, so
 * the storefront boots with the refresh cookie already set and exchanges it on its
 * first paint exactly as a returning visitor does. The best place for a credential is
 * the {@code HttpOnly} cookie it is already in, and nowhere else.
 */
@Component
public class OAuthSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuthSuccessHandler.class);

    private final OAuthAccountService oauthAccounts;
    private final AuthService authService;
    private final AppProperties props;

    public OAuthSuccessHandler(
            OAuthAccountService oauthAccounts, AuthService authService, AppProperties props) {
        this.oauthAccounts = oauthAccounts;
        this.authService = authService;
        this.props = props;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {

        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        String registration = token.getAuthorizedClientRegistrationId();
        OAuth2User user = token.getPrincipal();

        try {
            ProviderIdentity identity = read(registration, user);
            AccountEntity account = oauthAccounts.resolve(identity);
            AuthService.Session session = authService.issueFor(
                    account, request.getHeader(HttpHeaders.USER_AGENT), request.getRemoteAddr());

            response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie(session).toString());
            log.info("OAuth sign-in via {} for account {}", registration, account.getPublicId());
            /*
             * The session exists in the cookie by this point; the redirect carries
             * nothing but a destination. `session` is still needed above to mint it.
             */
            response.sendRedirect(redirect(""));
        } catch (OAuthAccountService.RefusedException refused) {
            // Logged without the address: a refusal is not a reason to write somebody's
            // email into the application log.
            log.info("OAuth sign-in via {} refused: {}", registration, refused.refusal());
            response.sendRedirect(redirect("?oauth_error=" + enc(refused.refusal().name())));
        }
    }

    /**
     * Each provider's user-info shape, written out rather than guessed at.
     *
     * <p>Google returns OIDC claims: {@code sub}, {@code email}, {@code email_verified}.
     * Discord returns its own object: {@code id} — a snowflake, and the only stable
     * handle it offers — plus {@code email}, {@code verified} and {@code username}.
     * Neither is the other, and treating them as one shape is how a subject ends up
     * null and every sign-in creates a fresh account.
     */
    private ProviderIdentity read(String registration, OAuth2User user) {
        if ("discord".equals(registration)) {
            Object verified = user.getAttribute("verified");
            String name = user.getAttribute("global_name");
            if (name == null || name.isBlank()) {
                name = user.getAttribute("username");
            }
            return new ProviderIdentity(
                    "discord",
                    user.getAttribute("id"),
                    user.getAttribute("email"),
                    Boolean.TRUE.equals(verified),
                    name);
        }
        Object verified = user.getAttribute("email_verified");
        return new ProviderIdentity(
                "google",
                user.getAttribute("sub"),
                user.getAttribute("email"),
                Boolean.TRUE.equals(verified),
                user.getAttribute("name"));
    }

    /**
     * The same cookie the password flow sets.
     *
     * <p>Kept identical on purpose — {@code SameSite=Strict} and {@code HttpOnly} are
     * what make the refresh token unreachable to script and unusable cross-site, and a
     * social sign-in has no business issuing a weaker one.
     */
    private ResponseCookie refreshCookie(AuthService.Session session) {
        return ResponseCookie.from(AuthController.REFRESH_COOKIE, session.refreshToken())
                .httpOnly(true)
                .secure(props.security().requireHttps())
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(props.security().refreshTokenTtl())
                .build();
    }

    /**
     * Where the browser lands afterwards.
     *
     * <p>The same origin the CORS policy already trusts, rather than a second setting
     * that can drift from it — a redirect target and an allowed origin disagreeing is
     * how a working deployment starts sending people somewhere the cookie is not valid.
     */
    private String redirect(String suffix) {
        var origins = props.security().corsAllowedOrigins();
        String base = (origins == null || origins.isEmpty()) ? "" : origins.get(0);
        return base + "/auth/callback" + suffix;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }
}
