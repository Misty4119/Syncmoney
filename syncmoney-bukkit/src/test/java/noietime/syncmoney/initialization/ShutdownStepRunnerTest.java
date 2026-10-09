package noietime.syncmoney.initialization;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShutdownStepRunnerTest {

    @Test
    void continuesInOrderAfterOwnerThrowsIncludingErrors() {
        List<String> calls = new ArrayList<>();

        ShutdownStepRunner.run(Logger.getAnonymousLogger(),
                new ShutdownStepRunner.Step("api", () -> calls.add("api")),
                new ShutdownStepRunner.Step("audit", () -> {
                    calls.add("audit");
                    throw new AssertionError("injected owner failure");
                }),
                new ShutdownStepRunner.Step("storage", () -> calls.add("storage")));

        assertEquals(List.of("api", "audit", "storage"), calls);
    }
}
