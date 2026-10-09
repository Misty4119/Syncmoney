package noietime.syncmoney.web.builder;

import noietime.syncmoney.Syncmoney;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Checks GitHub releases for the latest web frontend version.
 */
public class WebVersionChecker {

    private final Syncmoney plugin;
    private final String githubRepo;

    public WebVersionChecker(Syncmoney plugin, String githubRepo) {
        this.plugin = plugin;
        this.githubRepo = githubRepo;
    }

    /**
     * Check latest version from GitHub releases.
     * @return Latest version string, or null if failed
     */
    public String checkLatestVersion() {
        try {
            String apiUrl = "https://api.github.com/repos/" + githubRepo + "/releases/latest";

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String body = response.body();
                String tagName = extractTagName(body);
                if (tagName != null) {
                    return tagName.startsWith("v") ? tagName.substring(1) : tagName;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Version check failed: " + e.getMessage());
        }
        return null;
    }

    private String extractTagName(String json) {
        int start = json.indexOf("\"tag_name\":\"");
        if (start == -1) return null;
        start += 12;
        int end = json.indexOf("\"", start);
        if (end == -1) return null;
        return json.substring(start, end);
    }

    /**
     * Compare version strings.
     * @return true if latest > current
     */
    public boolean hasNewerVersion(String latest, String current) {
        if (latest == null || current == null) return false;

        int[] latestParts = parseVersion(latest);
        int[] currentParts = parseVersion(current);

        for (int i = 0; i < 3; i++) {
            if (latestParts[i] > currentParts[i]) return true;
            if (latestParts[i] < currentParts[i]) return false;
        }
        return false;
    }

    private int[] parseVersion(String version) {
        String[] parts = version.replace("v", "").split("\\.");
        int[] result = new int[3];
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            try {
                result[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }
}
