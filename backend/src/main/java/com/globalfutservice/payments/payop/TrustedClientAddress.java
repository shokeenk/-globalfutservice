package com.globalfutservice.payments.payop;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The address a request really came from, read only from what our own proxies wrote.
 *
 * <p>Spring's forwarded-header support takes the <em>left-most</em> X-Forwarded-For entry,
 * which is whatever the caller put there: fine for display, useless as a security check.
 * This reads the request underneath Spring's wrapper and trusts the chain from the right:
 *
 * <ol>
 *   <li>The connection itself comes from the hosting platform's proxy (a private address).
 *       That proxy appends the address it saw to X-Forwarded-For, so the right-most entry is
 *       the one value in the header the caller cannot choose.</li>
 *   <li>If that address is one of Cloudflare's, the request came through Cloudflare, which
 *       overwrites CF-Connecting-IP with the address that connected to it: that is the
 *       client.</li>
 *   <li>Otherwise the request reached the platform directly, and that address is the
 *       client.</li>
 * </ol>
 *
 * <p>A connection from a public address with no proxy in front (local development) is its
 * own client. Anything that does not fit -- no header where one must be, a value that is not
 * an IP address -- yields nothing, and a caller checking an allowlist must then refuse.
 */
public final class TrustedClientAddress {

    /*
     * IP literals only, so nothing here can ever trigger a DNS lookup: dotted-quad digits for
     * IPv4, and for IPv6 hex with at least one colon, which Java parses as a literal or refuses.
     */
    private static final Pattern IPV4 = Pattern.compile("[0-9]{1,3}([.][0-9]{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private final List<Cidr> cloudflare;

    public TrustedClientAddress(List<String> cloudflareRanges) {
        List<Cidr> ranges = new ArrayList<>();
        for (String range : cloudflareRanges == null ? List.<String>of() : cloudflareRanges) {
            if (range != null && !range.isBlank()) {
                ranges.add(Cidr.parse(range.trim()));
            }
        }
        this.cloudflare = Collections.unmodifiableList(ranges);
    }

    /** What the check concluded, for the log line. */
    public record Resolved(InetAddress client, boolean viaCloudflare) {
    }

    public Optional<Resolved> resolve(HttpServletRequest request) {
        HttpServletRequest raw = unwrap(request);
        Optional<InetAddress> peer = ip(raw.getRemoteAddr());
        if (peer.isEmpty()) {
            return Optional.empty();
        }
        if (!isInternal(peer.get())) {
            // Nothing in front of us: the connection is the client, and no header counts.
            return Optional.of(new Resolved(peer.get(), false));
        }
        List<String> forwarded = new ArrayList<>();
        for (java.util.Enumeration<String> values = raw.getHeaders("X-Forwarded-For"); values != null
                && values.hasMoreElements(); ) {
            for (String part : values.nextElement().split(",")) {
                if (!part.isBlank()) {
                    forwarded.add(part.trim());
                }
            }
        }
        if (forwarded.isEmpty()) {
            return Optional.empty();
        }
        Optional<InetAddress> seenByProxy = ip(forwarded.get(forwarded.size() - 1));
        if (seenByProxy.isEmpty()) {
            return Optional.empty();
        }
        if (!isCloudflare(seenByProxy.get())) {
            return Optional.of(new Resolved(seenByProxy.get(), false));
        }
        return ip(raw.getHeader("CF-Connecting-IP")).map(client -> new Resolved(client, true));
    }

    boolean isCloudflare(InetAddress address) {
        return cloudflare.stream().anyMatch(c -> c.contains(address));
    }

    /** Spring's forwarded-header filter wraps the request; the container's own is underneath. */
    static HttpServletRequest unwrap(HttpServletRequest request) {
        ServletRequest current = request;
        while (current instanceof ServletRequestWrapper wrapper) {
            current = wrapper.getRequest();
        }
        return current instanceof HttpServletRequest http ? http : request;
    }

    private static boolean isInternal(InetAddress address) {
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || isUniqueLocal(address);
    }

    /** fc00::/7, IPv6's private range, which Java does not count as site-local. */
    private static boolean isUniqueLocal(InetAddress address) {
        byte[] b = address.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;
    }

    /** Parses an IP literal ("1.2.3.4", "1.2.3.4:5678", "[::1]:80", "::1"); never a host name. */
    public static Optional<InetAddress> ip(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String v = value.trim();
        if (v.startsWith("[")) {
            int end = v.indexOf(']');
            if (end < 0) {
                return Optional.empty();
            }
            v = v.substring(1, end);
        } else if (v.indexOf(':') >= 0 && v.indexOf(':') == v.lastIndexOf(':') && v.indexOf('.') > 0) {
            v = v.substring(0, v.indexOf(':')); // IPv4 with a port
        }
        boolean literal = IPV4.matcher(v).matches() || (v.contains(":") && IPV6.matcher(v).matches());
        if (!literal) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(v));
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }

    /** An address range, IPv4 or IPv6. */
    record Cidr(byte[] network, int bits) {

        static Cidr parse(String cidr) {
            int slash = cidr.indexOf('/');
            String address = slash < 0 ? cidr : cidr.substring(0, slash);
            InetAddress parsed = ip(address).orElseThrow(() -> new IllegalArgumentException(
                    "'" + cidr + "' is not an address range"));
            int max = parsed.getAddress().length * 8;
            int bits = slash < 0 ? max : Integer.parseInt(cidr.substring(slash + 1));
            if (bits < 0 || bits > max) {
                throw new IllegalArgumentException("'" + cidr + "' is not an address range");
            }
            return new Cidr(parsed.getAddress(), bits);
        }

        boolean contains(InetAddress address) {
            byte[] a = address.getAddress();
            if (a.length != network.length) {
                return false;
            }
            int full = bits / 8;
            for (int i = 0; i < full; i++) {
                if (a[i] != network[i]) {
                    return false;
                }
            }
            int rest = bits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (a[full] & mask) == (network[full] & mask);
        }
    }
}
