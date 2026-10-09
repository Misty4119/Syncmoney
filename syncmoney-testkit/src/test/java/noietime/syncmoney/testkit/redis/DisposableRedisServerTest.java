package noietime.syncmoney.testkit.redis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisposableRedisServerTest {

    @Test
    void failedProcessLaunchRemovesOnlyTheOwnedTemporaryDirectory() throws Exception {
        Set<Path> before = ownedDirectories();
        String missingExecutable = Path.of(System.getProperty("java.io.tmpdir"),
                "missing-syncmoney-redis-" + UUID.randomUUID()).toString();

        assertThrows(IOException.class, () -> DisposableRedisServer.start(missingExecutable));

        assertEquals(before, ownedDirectories());
    }

    private static Set<Path> ownedDirectories() throws IOException {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(path -> path.getFileName().toString().startsWith("syncmoney-redis-test-"))
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
