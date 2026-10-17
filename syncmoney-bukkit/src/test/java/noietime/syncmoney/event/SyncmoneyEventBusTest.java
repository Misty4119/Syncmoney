package noietime.syncmoney.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SyncmoneyEventBus.
 */
class SyncmoneyEventBusTest {

    @Mock
    private noietime.syncmoney.Syncmoney mockPlugin;

    private SyncmoneyEventBus eventBus;

    @BeforeEach
    void setUp() {
        resetSingleton();
        MockitoAnnotations.openMocks(this);
        when(mockPlugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("Test"));
        SyncmoneyEventBus.init(mockPlugin);
        eventBus = SyncmoneyEventBus.getInstance();
    }

    @AfterEach
    void tearDown() {
        resetSingleton();
    }

    private static void resetSingleton() {
        try {
            Field instance = SyncmoneyEventBus.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to reset SyncmoneyEventBus test fixture", e);
        }
    }

    @Test
    void testRegisterAndCallEvent() {
        AtomicInteger callCount = new AtomicInteger(0);

        eventBus.register(PostTransactionEvent.class, event -> {
            callCount.incrementAndGet();
        }, EventPriority.NORMAL);

        PostTransactionEvent event = new PostTransactionEvent(
                UUID.randomUUID(),
                "TestPlayer",
                AsyncPreTransactionEvent.TransactionType.DEPOSIT,
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(0),
                BigDecimal.valueOf(100),
                "test",
                null,
                null,
                "test reason",
                true,
                null
        );

        eventBus.callEvent(event);

        assertEquals(1, callCount.get(), "Event should be called once");
    }

    @Test
    void testMultipleListeners() {
        AtomicInteger count1 = new AtomicInteger(0);
        AtomicInteger count2 = new AtomicInteger(0);

        eventBus.register(PostTransactionEvent.class, event -> count1.incrementAndGet());
        eventBus.register(PostTransactionEvent.class, event -> count2.incrementAndGet());

        PostTransactionEvent event = createTestEvent();
        eventBus.callEvent(event);

        assertEquals(1, count1.get());
        assertEquals(1, count2.get());
    }

    @Test
    void testPriorityOrdering() {
        AtomicInteger callOrder = new AtomicInteger(0);
        StringBuilder order = new StringBuilder();

        eventBus.register(TestEvent.class, event -> {
            order.append("LOWEST");
        }, EventPriority.LOWEST);

        eventBus.register(TestEvent.class, event -> {
            order.append("HIGHEST");
        }, EventPriority.HIGHEST);

        eventBus.register(TestEvent.class, event -> {
            order.append("NORMAL");
        }, EventPriority.NORMAL);

        TestEvent event = new TestEvent("TestEvent");
        eventBus.callEvent(event);

        assertTrue(order.toString().startsWith("HIGHEST"));
    }

    @Test
    void testEventWithNoListeners() {
        assertDoesNotThrow(() -> {
            PostTransactionEvent event = createTestEvent();
            eventBus.callEvent(event);
        });
    }

    @Test
    void testClearAll() {
        AtomicInteger count = new AtomicInteger(0);
        eventBus.register(PostTransactionEvent.class, event -> count.incrementAndGet());

        eventBus.clearAll();

        PostTransactionEvent event = createTestEvent();
        eventBus.callEvent(event);

        assertEquals(0, count.get(), "Listener should not be called after clearAll");
    }

    @Test
    void clearIfOwnedByLeavesAnotherPluginInstancesListenersUntouched() {
        SyncmoneyListener<PostTransactionEvent> sentinel = event -> { };
        int listenersBefore = eventBus.getListenerCount(PostTransactionEvent.class);
        eventBus.register(PostTransactionEvent.class, sentinel);

        SyncmoneyEventBus.clearIfOwnedBy(mock(noietime.syncmoney.Syncmoney.class));

        assertEquals(listenersBefore + 1, eventBus.getListenerCount(PostTransactionEvent.class));
        eventBus.unregister(PostTransactionEvent.class, sentinel);
    }

    @Test
    void clearIfOwnedByClearsListenersWhenPluginOwnsBus() {
        eventBus.register(PostTransactionEvent.class, event -> { });

        SyncmoneyEventBus.clearIfOwnedBy(mockPlugin);

        assertEquals(0, eventBus.getListenerCount(PostTransactionEvent.class));
    }

    @Test
    void testListenerCount() {
        assertEquals(0, eventBus.getListenerCount(PostTransactionEvent.class));

        eventBus.register(PostTransactionEvent.class, event -> {});
        assertEquals(1, eventBus.getListenerCount(PostTransactionEvent.class));

        eventBus.register(PostTransactionEvent.class, event -> {});
        assertEquals(2, eventBus.getListenerCount(PostTransactionEvent.class));

        eventBus.clearAll();
        assertEquals(0, eventBus.getListenerCount(PostTransactionEvent.class));
    }

    @Test
    void testCallEventSync() {
        AtomicInteger count = new AtomicInteger(0);

        eventBus.register(TestEvent.class, event -> count.incrementAndGet());

        TestEvent event = new TestEvent("SyncTest");
        // callEventSync routes through Bukkit.isPrimaryThread(); stub it so the
        // event is dispatched synchronously in the test JVM (no live server).
        try (MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::isPrimaryThread).thenReturn(true);
            eventBus.callEventSync(event);
        }

        assertEquals(1, count.get());
    }

    private PostTransactionEvent createTestEvent() {
        return new PostTransactionEvent(
                UUID.randomUUID(),
                "TestPlayer",
                AsyncPreTransactionEvent.TransactionType.DEPOSIT,
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(0),
                BigDecimal.valueOf(100),
                "test",
                null,
                null,
                "test reason",
                true,
                null
        );
    }

    /**
     * Simple test event for testing.
     */
    private static class TestEvent extends SyncmoneyEvent {
        public TestEvent(String name) {
            super(name);
        }
    }
}
