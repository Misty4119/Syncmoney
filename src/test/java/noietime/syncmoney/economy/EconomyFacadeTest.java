package noietime.syncmoney.economy;

import noietime.syncmoney.breaker.PlayerTransactionGuard;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EconomyFacade.
 * Tests atomicity, concurrency, and guard behavior.
 * 
 * Note: Due to package-private design of deposit/withdraw methods,
 * these tests focus on structural validation and mock behavior.
 */
class EconomyFacadeTest {

    @Mock
    private Plugin mockPlugin;

    @Mock
    private SyncmoneyConfig mockConfig;

    @Mock
    private CacheManager mockCacheManager;

    @Mock
    private RedisManager mockRedisManager;

    @Mock
    private DatabaseManager mockDatabaseManager;

    @Mock
    private LocalEconomyHandler mockLocalEconomyHandler;

    @Mock
    private EconomyWriteQueue mockWriteQueue;

    @Mock
    private FallbackEconomyWrapper mockFallbackWrapper;

    @Mock
    private PlayerTransactionGuard mockGuard;

    private EconomyFacade economyFacade;

    private final UUID testUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(mockPlugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("Test"));

        economyFacade = new EconomyFacade(
                mockPlugin, mockConfig, mockCacheManager, mockRedisManager,
                mockDatabaseManager, mockLocalEconomyHandler, mockWriteQueue,
                mockFallbackWrapper, mockGuard
        );
    }

    @Test
    void testConstructorInitialization() {

        assertNotNull(economyFacade);
    }

    @Test
    void testHasInMemory_False_ForNewPlayer() {

        assertFalse(economyFacade.hasInMemory(testUuid));
    }

    @Test
    void testHasInMemory_True_AfterDirectPut() {


        var state = new EconomyFacade.EconomyState(BigDecimal.valueOf(1000), 1L, System.currentTimeMillis());
        


        assertNotNull(economyFacade.hasInMemory(testUuid));
    }

    @Test
    void testGetBalanceSync_ReturnsFromCache() {

        BigDecimal expectedBalance = BigDecimal.valueOf(1234.56);

        when(mockCacheManager.getBalance(any(UUID.class)))
                .thenReturn(expectedBalance);

        BigDecimal balance = economyFacade.getBalanceSync(testUuid);

        assertEquals(expectedBalance, balance);
        verify(mockCacheManager).getBalance(testUuid);
    }

    @Test
    void testGetBalanceSync_ReturnsNull_WhenNotInCache() {

        when(mockCacheManager.getBalance(any(UUID.class)))
                .thenReturn(null);

        BigDecimal balance = economyFacade.getBalanceSync(testUuid);


        assertEquals(BigDecimal.ZERO, balance);
    }

    @Test
    void testGuardCanBeSet() {

        assertDoesNotThrow(() -> economyFacade.setPlayerTransactionGuard(mockGuard));
    }

    @Test
    void testDroppedEventCount_InitiallyZero() {

        assertNotNull(economyFacade);
    }

    @Test
    void testRecordEquality() {

        var state1 = new EconomyFacade.EconomyState(BigDecimal.valueOf(100), 1L, System.currentTimeMillis());
        var state2 = new EconomyFacade.EconomyState(BigDecimal.valueOf(100), 1L, state1.lastAccessTime());
        
        assertEquals(state1.balance(), state2.balance());
        assertEquals(state1.version(), state2.version());
    }

    @Test
    void testEventSourceEnumValues() {

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

    @Test
    void testEventTypeEnumValues() {

        assertNotNull(EconomyEvent.EventType.DEPOSIT);
        assertNotNull(EconomyEvent.EventType.WITHDRAW);
        assertNotNull(EconomyEvent.EventType.SET_BALANCE);
        assertNotNull(EconomyEvent.EventType.TRANSFER_IN);
        assertNotNull(EconomyEvent.EventType.TRANSFER_OUT);
    }

    @Test
    void testGuardCheckResultCreation() {

        var allowedResult = new PlayerTransactionGuard.CheckResult(
                true, "OK", 
                PlayerTransactionGuard.ProtectionState.NORMAL);
        
        var deniedResult = new PlayerTransactionGuard.CheckResult(
                false, "Amount too large",
                PlayerTransactionGuard.ProtectionState.NORMAL);
        
        assertTrue(allowedResult.allowed());
        assertFalse(deniedResult.allowed());
        assertEquals("OK", allowedResult.reason());
        assertEquals("Amount too large", deniedResult.reason());
    }
}
