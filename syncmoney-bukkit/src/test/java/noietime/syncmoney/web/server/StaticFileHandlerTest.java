package noietime.syncmoney.web.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StaticFileHandler} path-traversal protection and sanitisation.
 */
class StaticFileHandlerTest {

    // ─── resolveSafe: traversal protection ──────────────────────────────────

    @Test
    void normalFileResolvesInsideRoot(@TempDir Path root) {
        Path resolved = StaticFileHandler.resolveSafe(root, "index.html");
        assertNotNull(resolved, "A plain file name should resolve");
        assertTrue(resolved.startsWith(root.toAbsolutePath().normalize()),
                "Resolved file must stay within the web root");
    }

    @Test
    void nestedAssetResolvesInsideRoot(@TempDir Path root) {
        Path resolved = StaticFileHandler.resolveSafe(root, "assets/app.js");
        assertNotNull(resolved);
        assertTrue(resolved.startsWith(root.toAbsolutePath().normalize()));
    }

    @Test
    void parentTraversalIsRejected(@TempDir Path root) {
        assertNull(StaticFileHandler.resolveSafe(root, "../secret.txt"),
                "A single ../ escape must be rejected");
    }

    @Test
    void deepParentTraversalIsRejected(@TempDir Path root) {
        assertNull(StaticFileHandler.resolveSafe(root, "../../../../etc/passwd"),
                "Repeated ../ escapes must be rejected");
    }

    @Test
    void embeddedTraversalSegmentIsRejected(@TempDir Path root) {
        assertNull(StaticFileHandler.resolveSafe(root, "assets/../../etc/passwd"),
                "A .. segment nested in the path must be rejected");
    }

    @Test
    void nullInputsAreRejected(@TempDir Path root) {
        assertNull(StaticFileHandler.resolveSafe(root, null));
        assertNull(StaticFileHandler.resolveSafe(null, "index.html"));
    }

    // ─── sanitize: mirrors original sanitisation rules ──────────────────────

    @Test
    void sanitizeDefaultsToIndexForEmpty() {
        assertEquals("index.html", StaticFileHandler.sanitize(""));
        assertEquals("index.html", StaticFileHandler.sanitize("/"));
    }

    @Test
    void sanitizeStripsLeadingSlash() {
        assertEquals("assets/app.js", StaticFileHandler.sanitize("/assets/app.js"));
    }

    @Test
    void sanitizeStripsDisallowedCharacters() {
        // Spaces and other special chars are removed by the allowlist regex.
        assertEquals("appscript.js", StaticFileHandler.sanitize("app<script>.js"));
    }

    @Test
    void sanitizedTraversalIsStillRejectedByResolveSafe(@TempDir Path root) {
        // End-to-end: even after sanitisation a ../ path must be blocked on resolve.
        String sanitized = StaticFileHandler.sanitize("/../../secret");
        assertNull(StaticFileHandler.resolveSafe(root, sanitized));
    }
}
