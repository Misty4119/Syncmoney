package noietime.syncmoney.web.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Shared HTTP client utility for inter-node (server-to-server) requests.
 *
 * <p>Previously every caller created its own {@link HttpClient} with hand-copied
 * connect/read timeouts. This util centralises a single reusable client and a
 * Bearer-authenticated GET helper so the timeout policy lives in one place.</p>
 *
 * <p>Default timeouts preserve the values previously used by the call sites:
 * a 5s connect timeout and a 10s per-request timeout.</p>
 */
public final class HttpClientUtil {

    public static final long DEFAULT_CONNECT_TIMEOUT_MS = 5_000L;
    public static final long DEFAULT_READ_TIMEOUT_MS = 10_000L;

    private static final HttpClient SHARED_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(DEFAULT_CONNECT_TIMEOUT_MS))
            .build();

    private HttpClientUtil() {
    }

    /**
     * Get the shared {@link HttpClient} (5s connect timeout).
     */
    public static HttpClient shared() {
        return SHARED_CLIENT;
    }

    /**
     * Perform a Bearer-authenticated GET request using the shared client and the
     * default read timeout.
     */
    public static HttpResponse<String> getWithBearer(String url, String apiKey)
            throws java.io.IOException, InterruptedException {
        return getWithBearer(url, apiKey, DEFAULT_READ_TIMEOUT_MS);
    }

    /**
     * Perform a Bearer-authenticated GET request using the shared client.
     *
     * @param url         the absolute URL
     * @param apiKey      the (decrypted) API key sent as {@code Authorization: Bearer ...}
     * @param readTimeoutMs per-request timeout in milliseconds
     */
    public static HttpResponse<String> getWithBearer(String url, String apiKey, long readTimeoutMs)
            throws java.io.IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMillis(readTimeoutMs))
                .GET()
                .build();
        return SHARED_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
