package noietime.syncmoney.util;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertFalse;

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
        Path libs = Path.of("build", "libs");
        Assumptions.assumeTrue(Files.isDirectory(libs), "shadow jar has not been built");

        Path jar = Files.list(libs)
                .filter(path -> path.getFileName().toString().startsWith("Syncmoney-"))
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
                .max(Comparator.comparingLong(this::lastModified))
                .orElseThrow(() -> new AssertionError("No Syncmoney shadow jar found"));

        try (JarFile jarFile = new JarFile(jar.toFile())) {
            boolean hasBundledAdventure = jarFile.stream()
                    .map(entry -> entry.getName().replace('\\', '/'))
                    .anyMatch(name -> name.startsWith("net/kyori/adventure/")
                            || name.startsWith("noietime/libs/adventure/"));

            assertFalse(hasBundledAdventure,
                    "Syncmoney must use the server's native Adventure runtime, not a partial shaded copy");
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }
}
