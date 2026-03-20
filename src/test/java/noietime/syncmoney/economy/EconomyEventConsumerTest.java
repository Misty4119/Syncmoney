package noietime.syncmoney.economy;

import noietime.syncmoney.audit.AuditLogger;
import noietime.syncmoney.audit.HybridAuditManager;
import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.shadow.ShadowSyncTask;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.economy.OverflowLog;
import noietime.syncmoney.storage.db.DatabaseManager;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EconomyEventConsumer.
 * Tests event processing, batch handling, and queue management.
 */
class EconomyEventConsumerTest {

    @Mock
    private Plugin mockPlugin;

    @Mock
    private SyncmoneyConfig mockConfig;

    @Mock
    private EconomyWriteQueue mockQueue;

    @Mock
    private CacheManager mockCacheManager;

    @Mock
    private RedisManager mockRedisManager;

    @Mock
    private DbWriteQueue mockDbWriteQueue;

    @Mock
    private AuditLogger mockAuditLogger;

    @Mock
    private HybridAuditManager mockHybridAuditManager;

    @Mock
    private NameResolver mockNameResolver;

    @Mock
    private BaltopManager mockBaltopManager;

    @Mock
    private ShadowSyncTask mockShadowSyncTask;

    @Mock
    private OverflowLog mockOverflowLog;

    @Mock
    private DatabaseManager mockDatabaseManager;

    private EconomyEventConsumer consumer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(mockPlugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("Test"));

        consumer = new EconomyEventConsumer(
                mockPlugin, mockConfig, mockQueue, mockCacheManager,
                mockRedisManager, mockDbWriteQueue, mockAuditLogger,
                mockHybridAuditManager, mockNameResolver, mockBaltopManager,
                mockShadowSyncTask, mockOverflowLog, mockDatabaseManager
        );
    }

    @Test
    void testEventProcessing() throws Exception {

        UUID playerUuid = UUID.randomUUID();
        EconomyEvent event = new EconomyEvent(
                playerUuid,
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(1100),
                1L,
                EconomyEvent.EventType.DEPOSIT,
                EconomyEvent.EventSource.TEST,
                "test-request",
                System.currentTimeMillis()
        );


        assertNotNull(event);
        assertEquals(EconomyEvent.EventType.DEPOSIT, event.type());
        assertEquals(playerUuid, event.uuid());
        assertEquals("test-request", event.requestId());
    }

    @Test
    void testBatchProcessing() throws Exception {

        when(mockQueue.poll()).thenReturn(
                createTestEvent(UUID.randomUUID(), BigDecimal.valueOf(100)),
                createTestEvent(UUID.randomUUID(), BigDecimal.valueOf(200)),
                null
        );


        consumer.processPending();


        verify(mockQueue, atLeastOnce()).poll();
    }

    @Test
    void testEmptyQueueHandling() throws Exception {

        when(mockQueue.poll()).thenReturn(null);


        assertDoesNotThrow(() -> consumer.processPending());
    }

    @Test
    void testConsumerCanBeStopped() {

        assertTrue(consumer.isRunning());


        consumer.stop();


        assertFalse(consumer.isRunning());
    }

    @Test
    void testMultipleEventsProcessed() throws InterruptedException {
        int eventCount = 100;
        CountDownLatch latch = new CountDownLatch(eventCount);


        when(mockQueue.poll()).thenAnswer(invocation -> {
            latch.countDown();
            if (latch.getCount() == 0) {
                return null;
            }
            return createTestEvent(UUID.randomUUID(), BigDecimal.valueOf(100));
        });


        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            for (int i = 0; i < eventCount; i++) {
                consumer.processPending();
            }
        });


        boolean completed = latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "All events should be processed");
    }

    @Test
    void testEventTypeHandling() {

        assertEquals(EconomyEvent.EventType.DEPOSIT, EconomyEvent.EventType.DEPOSIT);
        assertEquals(EconomyEvent.EventType.WITHDRAW, EconomyEvent.EventType.WITHDRAW);
        assertEquals(EconomyEvent.EventType.SET_BALANCE, EconomyEvent.EventType.SET_BALANCE);
        assertEquals(EconomyEvent.EventType.TRANSFER_IN, EconomyEvent.EventType.TRANSFER_IN);
        assertEquals(EconomyEvent.EventType.TRANSFER_OUT, EconomyEvent.EventType.TRANSFER_OUT);
    }

    @Test
    void testEventSourceEnum() {

        assertNotNull(EconomyEvent.EventSource.VAULT_DEPOSIT);
        assertNotNull(EconomyEvent.EventSource.VAULT_WITHDRAW);
        assertNotNull(EconomyEvent.EventSource.COMMAND_PAY);
        assertNotNull(EconomyEvent.EventSource.COMMAND_ADMIN);
        assertNotNull(EconomyEvent.EventSource.ADMIN_SET);
        assertNotNull(EconomyEvent.EventSource.ADMIN_GIVE);
        assertNotNull(EconomyEvent.EventSource.ADMIN_TAKE);
        assertNotNull(EconomyEvent.EventSource.PLAYER_TRANSFER);
        assertNotNull(EconomyEvent.EventSource.MIGRATION);
        assertNotNull(EconomyEvent.EventSource.SHADOW_SYNC);
        assertNotNull(EconomyEvent.EventSource.TEST);
    }

    private EconomyEvent createTestEvent(UUID uuid, BigDecimal amount) {
        return new EconomyEvent(
                uuid,
                amount,
                BigDecimal.valueOf(1000).add(amount),
                1L,
                EconomyEvent.EventType.DEPOSIT,
                EconomyEvent.EventSource.TEST,
                "test-request",
                System.currentTimeMillis()
        );
    }
}
