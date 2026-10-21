package com.globalfutservice.popups;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Where a popup button may send someone: a path on this site, or an https link on
 * globalfutservices.com. Nothing else -- no other domain, no http, no javascript:.
 *
 * <p>Checked on save, so a popup cannot be published that links off-site. The storefront
 * opens a path with its own router and a full link in the same tab.
 */
public final class PopupDestinations {

    private static final Set<String> HOSTS = Set.of("globalfutservices.com", "www.globalfutservices.com");

    /** A path: one slash, then only URL-safe characters. Not "//host", not a backslash. */
    private static final Pattern PATH = Pattern.compile("^/(?![/\\\\])[A-Za-z0-9\\-._~/%?=&#+]*$");

    /** Pages are paths too, for "selected pages": no query or fragment there. */
    private static final Pattern PAGE_PATH = Pattern.compile("^/(?![/\\\\])[A-Za-z0-9\\-._~/]*$");

    private PopupDestinations() {
    }

    /** The destination as stored, or a reason it is refused. */
    public static Optional<String> problem(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of("Every button needs a destination.");
        }
        String value = raw.trim();
        if (value.length() > 300) {
            return Optional.of("That destination is too long.");
        }
        if (value.startsWith("/")) {
            return PATH.matcher(value).matches() ? Optional.empty()
                    : Optional.of("\"" + value + "\" is not a path on this site.");
        }
        try {
            URI uri = new URI(value);
            boolean ok = "https".equals(uri.getScheme())
                    && uri.getRawUserInfo() == null
                    && uri.getPort() == -1
                    && uri.getHost() != null
                    && HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
                    && uri.getRawAuthority().equalsIgnoreCase(uri.getHost());
            return ok ? Optional.empty()
                    : Optional.of("Links must be a path on this site or start with https://globalfutservices.com.");
        } catch (URISyntaxException e) {
            return Optional.of("\"" + value + "\" is not a link.");
        }
    }

    /** A "selected pages" entry: a bare path on this site. */
    public static boolean isPagePath(String raw) {
        return raw != null && raw.length() <= 200 && PAGE_PATH.matcher(raw.trim()).matches();
    }
}
