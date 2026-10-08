package noietime.syncmoney.protocol;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ProtocolDependencyBoundaryTest {

    @Test
    void protocolTestRuntimeDoesNotPullPlatformOrTransportDependencies() {
        String classpath = System.getProperty("java.class.path").toLowerCase(Locale.ROOT);

        assertFalse(classpath.contains("paper-api"));
        assertFalse(classpath.contains("jedis"));
        assertFalse(classpath.contains("jdbc"));
        assertFalse(classpath.contains("cmi"));
    }
}
