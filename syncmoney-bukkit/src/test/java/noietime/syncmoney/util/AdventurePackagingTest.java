package noietime.syncmoney.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the Canvas/Paper Adventure class-loader boundary.
 *
 * <p>The runtime server owns Adventure. A plugin jar must therefore not contain
 * a partially relocated MiniMessage/serializer tree alongside the server's
 * unrelocated Adventure API.</p>
 */
class AdventurePackagingTest {

    @Test
    void runtimeJarDoesNotContainPartiallyRelocatedAdventure() throws IOException {
        String configuredPath = System.getProperty("syncmoney.shadowJar");
        assertNotNull(configuredPath, "root shadowJar path must be configured for packaging tests");
        Path jar = Path.of(configuredPath);
        assertTrue(Files.isRegularFile(jar), "root shadow jar is missing: " + jar);

        try (JarFile jarFile = new JarFile(jar.toFile())) {
            boolean hasBundledAdventure = jarFile.stream()
                    .map(entry -> entry.getName().replace('\\', '/'))
                    .anyMatch(name -> name.startsWith("net/kyori/adventure/")
                            || name.startsWith("noietime/libs/adventure/"));

            assertFalse(hasBundledAdventure,
                    "Syncmoney must use the server's native Adventure runtime, not a partial shaded copy");
        }
    }
}