package com.globalfutservice.payments.payop;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedClientAddressTest {

    private static final List<String> CLOUDFLARE = List.of("173.245.48.0/20", "172.64.0.0/13", "2606:4700::/32");
    private static final TrustedClientAddress ADDRESSES = new TrustedClientAddress(CLOUDFLARE);
    private static final String PAYOP = "18.199.249.46";
    private static final String RENDER_PROXY = "10.204.3.17";
    private static final String CLOUDFLARE_EDGE = "172.70.1.1";

    private static MockHttpServletRequest request(String peer, String forwardedFor, String cfConnectingIp) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/payments/payop/callback");
        r.setRemoteAddr(peer);
        if (forwardedFor != null) {
            r.addHeader("X-Forwarded-For", forwardedFor);
        }
        if (cfConnectingIp != null) {
            r.addHeader("CF-Connecting-IP", cfConnectingIp);
        }
        return r;
    }

    private static Optional<String> client(HttpServletRequest r) {
        return ADDRESSES.resolve(r).map(resolved -> resolved.client().getHostAddress());
    }

    @Test
    @DisplayName("through Cloudflare: the client is the address Cloudflare saw, not anything the caller wrote")
    void throughCloudflare() {
        MockHttpServletRequest r = request(RENDER_PROXY, "6.6.6.6, " + PAYOP + ", " + CLOUDFLARE_EDGE, PAYOP);
        assertThat(ADDRESSES.resolve(r)).hasValueSatisfying(resolved -> {
            assertThat(resolved.client().getHostAddress()).isEqualTo(PAYOP);
            assertThat(resolved.viaCloudflare()).isTrue();
        });
    }

    /*
     * Production: Payop -> [Cloudflare] -> Render's router -> the storefront's nginx -> the
     * API. Render's router appends the address it saw; nginx appends the router's own, a
     * private address. Taking the right-most entry as it stood read that private address, so
     * every real IPN was refused.
     */
    private static final String STOREFRONT = "10.230.0.5";

    @Test
    @DisplayName("through the storefront's nginx: our private hops are skipped, the address Render saw is the client")
    void throughNginx() {
        assertThat(client(request(STOREFRONT, PAYOP + ", " + RENDER_PROXY, null))).contains(PAYOP);
    }

    @Test
    @DisplayName("through Cloudflare and the storefront's nginx: Cloudflare's client, past both private hops")
    void throughCloudflareAndNginx() {
        MockHttpServletRequest r = request(STOREFRONT, "6.6.6.6, " + PAYOP + ", " + CLOUDFLARE_EDGE + ", " + RENDER_PROXY,
                PAYOP);
        assertThat(ADDRESSES.resolve(r)).hasValueSatisfying(resolved -> {
            assertThat(resolved.client().getHostAddress()).isEqualTo(PAYOP);
            assertThat(resolved.viaCloudflare()).isTrue();
        });
    }

    @Test
    @DisplayName("forged behind nginx: what the caller wrote is never reached, the address Render saw wins")
    void forgedBehindNginx() {
        MockHttpServletRequest r = request(STOREFRONT, PAYOP + ", 198.51.100.7, " + RENDER_PROXY, PAYOP);
        assertThat(client(r)).contains("198.51.100.7");
    }

    @Test
    @DisplayName("only private addresses in the chain, or a non-address on the way: nothing, so refused")
    void onlyPrivate() {
        assertThat(client(request(STOREFRONT, "10.1.1.1, " + RENDER_PROXY, null))).isEmpty();
        assertThat(client(request(STOREFRONT, PAYOP + ", payop.example, " + RENDER_PROXY, null))).isEmpty();
    }

    @Test
    @DisplayName("straight to the host with forged headers: the address the host's proxy saw wins")
    void forgedDirect() {
        // The attacker wrote Payop's address into both headers; the platform's proxy then
        // appended the attacker's real address, which is not Cloudflare's.
        MockHttpServletRequest r = request(RENDER_PROXY, PAYOP + ", 198.51.100.7", PAYOP);
        assertThat(client(r)).contains("198.51.100.7");
    }

    @Test
    @DisplayName("Spring's forwarded-header wrapper (left-most entry) is looked through")
    void wrapperIgnored() {
        MockHttpServletRequest raw = request(RENDER_PROXY, PAYOP + ", 198.51.100.7", null);
        HttpServletRequest wrapped = new HttpServletRequestWrapper(raw) {
            @Override
            public String getRemoteAddr() {
                return PAYOP; // what ForwardedHeaderFilter would report: the caller's own claim
            }
        };
        assertThat(wrapped.getRemoteAddr()).isEqualTo(PAYOP);
        assertThat(client(wrapped)).contains("198.51.100.7");
    }

    @Test
    @DisplayName("Cloudflare without CF-Connecting-IP, or a proxy without X-Forwarded-For: no address, so refused")
    void failsClosed() {
        assertThat(client(request(RENDER_PROXY, CLOUDFLARE_EDGE, null))).isEmpty();
        assertThat(client(request(RENDER_PROXY, null, PAYOP))).isEmpty();
        assertThat(client(request(RENDER_PROXY, "not-an-ip", PAYOP))).isEmpty();
        assertThat(client(request(RENDER_PROXY, CLOUDFLARE_EDGE, "payop.com"))).isEmpty();
    }

    @Test
    @DisplayName("no proxy in front (a public peer): the connection is the client and headers count for nothing")
    void noProxy() {
        assertThat(client(request("203.0.113.50", PAYOP, PAYOP))).contains("203.0.113.50");
    }

    @Test
    @DisplayName("only IP literals are read, so no header value can make the server look a name up")
    void literalsOnly() {
        assertThat(TrustedClientAddress.ip("ca.fe")).isEmpty();
        assertThat(TrustedClientAddress.ip("evil.example")).isEmpty();
        assertThat(TrustedClientAddress.ip("cafe")).isEmpty();
        assertThat(TrustedClientAddress.ip("1.2.3")).isEmpty();
        assertThat(TrustedClientAddress.ip("1.2.3.4:5678")).map(InetAddress::getHostAddress).contains("1.2.3.4");
        assertThat(TrustedClientAddress.ip("[2001:db8::1]:443")).map(InetAddress::getHostAddress)
                .contains("2001:db8:0:0:0:0:0:1");
        assertThat(TrustedClientAddress.ip(" 18.199.249.46 ")).map(InetAddress::getHostAddress).contains(PAYOP);
    }

    @Test
    @DisplayName("ranges match to the bit, IPv4 and IPv6")
    void ranges() {
        assertThat(ADDRESSES.isCloudflare(TrustedClientAddress.ip("172.64.0.0").orElseThrow())).isTrue();
        assertThat(ADDRESSES.isCloudflare(TrustedClientAddress.ip("172.71.255.255").orElseThrow())).isTrue();
        assertThat(ADDRESSES.isCloudflare(TrustedClientAddress.ip("172.72.0.0").orElseThrow())).isFalse();
        assertThat(ADDRESSES.isCloudflare(TrustedClientAddress.ip("173.245.63.255").orElseThrow())).isTrue();
        assertThat(ADDRESSES.isCloudflare(TrustedClientAddress.ip("173.245.64.0").orElseThrow())).isFalse();
        assertThat(ADDRESSES.isCloudflare(TrustedClientAddress.ip("2606:4700:10::1").orElseThrow())).isTrue();
        assertThat(ADDRESSES.isCloudflare(TrustedClientAddress.ip("2606:4701::1").orElseThrow())).isFalse();
        assertThat(ADDRESSES.isCloudflare(TrustedClientAddress.ip(PAYOP).orElseThrow())).isFalse();
    }
}
