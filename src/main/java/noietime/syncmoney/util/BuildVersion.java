package noietime.syncmoney.util;

import java.io.IOException;
import java.util.Properties;

/** Build metadata expanded from the root Gradle version; does not require a running server. */
public final class BuildVersion {
    public static final String VERSION = load();
    private BuildVersion() {}

    private static String load() {
        try (var stream = BuildVersion.class.getResourceAsStream("/syncmoney-version.properties")) {
            if (stream == null) return "development";
            var properties = new Properties();
            properties.load(stream);
            return properties.getProperty("version", "development");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read Syncmoney build version", e);
        }
    }
}
