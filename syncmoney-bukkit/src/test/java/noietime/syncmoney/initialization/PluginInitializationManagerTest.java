package noietime.syncmoney.initialization;

import noietime.syncmoney.Syncmoney;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PluginInitializationManagerTest {

    @Test
    void failedInitializationStopsLaterStagesAndRollsBackEveryAttemptedStageInReverse() {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        PluginInitializationManager manager = new PluginInitializationManager(plugin);
        List<String> calls = new ArrayList<>();

        manager.registerStep(step("storage", calls, false, false));
        manager.registerStep(step("economy", calls, true, true));
        manager.registerStep(step("listeners", calls, false, false));

        assertThrows(Initializable.InitializationException.class, manager::initialize);

        assertEquals(List.of("init:storage", "init:economy", "close:economy", "close:storage"), calls);
        assertFalse(manager.isInitialized());
    }

    @Test
    void shutdownAttemptsEveryOwnerInReverseEvenWhenOneCloseThrows() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        PluginInitializationManager manager = new PluginInitializationManager(plugin);
        List<String> calls = new ArrayList<>();
        manager.registerStep(step("storage", calls, false, false));
        manager.registerStep(step("economy", calls, false, true));
        manager.initialize();

        manager.shutdown();

        assertEquals(List.of("init:storage", "init:economy", "close:economy", "close:storage"), calls);
        assertFalse(manager.isInitialized());
    }

    private static Initializable step(String name, List<String> calls, boolean failInit, boolean failClose) {
        return new Initializable() {
            @Override
            public void initialize() throws InitializationException {
                calls.add("init:" + name);
                if (failInit) {
                    throw new InitializationException(name + " initialization failed");
                }
            }

            @Override
            public void shutdown() {
                calls.add("close:" + name);
                if (failClose) {
                    throw new IllegalStateException(name + " close failed");
                }
            }
        };
    }
}
