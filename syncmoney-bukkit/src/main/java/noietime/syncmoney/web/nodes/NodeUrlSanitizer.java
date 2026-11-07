package noietime.syncmoney.web.nodes;

import java.net.URI;

/**
 * Sanitizes configured node URLs before they leave the server in API or event output.
 * User-info, query parameters, and fragments are intentionally omitted.
 */
public final class NodeUrlSanitizer {

    private NodeUrlSanitizer() {
    }

    public static String sanitizeForDisplay(String url) {
        if (url == null || url.isBlank()) return url;
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String rawAuthority = uri.getRawAuthority();
            if (!uri.isAbsolute() || uri.isOpaque() || uri.getHost() == null || rawAuthority == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return "[invalid URL]";
            }

            if (uri.getRawUserInfo() != null) {
                int userInfoEnd = rawAuthority.lastIndexOf('@');
                if (userInfoEnd < 0) return "[invalid URL]";
                rawAuthority = rawAuthority.substring(userInfoEnd + 1);
            }

            String rawPath = uri.getRawPath();
            return scheme + "://" + rawAuthority + (rawPath == null ? "" : rawPath);
        } catch (Exception ignored) {
            return "[invalid URL]";
        }
    }
}
