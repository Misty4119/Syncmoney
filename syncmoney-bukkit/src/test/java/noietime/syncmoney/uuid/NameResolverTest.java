package noietime.syncmoney.uuid;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NameResolverTest {

    @Test
    void placeholderNameMissesHaveBoundedAsyncAdmission() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        List<Consumer<ScheduledTask>> pendingLookups = new ArrayList<>();
        doAnswer(invocation -> {
            pendingLookups.add(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(asyncScheduler).runNow(eq(plugin), any());
        NameResolver resolver = new NameResolver(plugin, null, null);

        assertNull(resolver.resolveUUIDForPlaceholder("pending-name-0"));
        assertNull(resolver.resolveUUIDForPlaceholder("pending-name-0"),
                "a duplicate name shares its already admitted lookup");
        for (int i = 1; i < NameResolver.MAX_PENDING_NAME_LOOKUPS + 100; i++) {
            assertNull(resolver.resolveUUIDForPlaceholder("pending-name-" + i));
        }

        assertEquals(NameResolver.MAX_PENDING_NAME_LOOKUPS, pendingLookups.size(),
                "unique cold names cannot enqueue unbounded Paper async work");
        verify(asyncScheduler, org.mockito.Mockito.times(NameResolver.MAX_PENDING_NAME_LOOKUPS))
                .runNow(eq(plugin), any());
    }
}
