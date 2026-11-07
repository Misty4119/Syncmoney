package noietime.syncmoney.sync;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.CrossServerSyncManager;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyWriteQueue;
import noietime.syncmoney.economy.FallbackEconomyWrapper;
import noietime.syncmoney.economy.OverflowLogInterface;
import noietime.syncmoney.protocol.RedisChannels;
import noietime.syncmoney.protocol.RedisKeys;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.testkit.RedisTestSupport;
import noietime.syncmoney.testkit.redis.DisposableRedisServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.params.ClientKillParams;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/** Real-Redis coverage for missed Pub/Sub events and authority reconciliation. */
class RedisReconciliationIT {
    private static DisposableRedisServer redis;
    private final List<CacheManager> cacheManagers = new ArrayList<>();

    @BeforeAll
    static void startRedis() throws Exception {
        redis = DisposableRedisServer.start();
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (redis != null) {
            redis.close();
        }
    }

    @BeforeEach
    void clearRedis() {
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            jedis.flushDB();
        }
    }

    @AfterEach
    void stopCacheManagers() {
        cacheManagers.forEach(CacheManager::shutdown);
        cacheManagers.clear();
    }

    @Test
    void syncManagerReconcilesFromRedisInsteadOfItsStaleMemoryFirstCache() throws Exception {
        Syncmoney plugin = plugin();
        RedisManager redisManager = redisManager(null);
        CacheManager cacheManager = cacheManager(plugin, redisManager);
        UUID account = UUID.randomUUID();
        MemoryStateProbe memory = new MemoryStateProbe(cacheManager);
        memory.seed(account, new BigDecimal("10.00"), 1L);
        setAuthority(account, new BigDecimal("20.00"), 2L);

        SyncManager manager = new SyncManager(plugin, config("server-b"), cacheManager,
                memory.facade(), redisManager);
        invoke(manager, "performVersionCheck");

        assertEquals(new BigDecimal("20.00"), memory.state(account).balance());
        assertEquals(2L, memory.state(account).version());
        assertEquals(1, memory.remoteUpdates());
    }

    @Test
    void crossServerReconcileUsesOneRedisBalanceVersionSnapshot() throws Exception {
        Syncmoney plugin = plugin();
        UUID account = UUID.randomUUID();
        AtomicBoolean mutateAuthorityAfterRead = new AtomicBoolean(false);
        RedisManager redisManager = redisManager(mutateAuthorityAfterRead, account);
        CacheManager cacheManager = cacheManager(plugin, redisManager);
        MemoryStateProbe memory = new MemoryStateProbe(cacheManager);
        memory.seed(account, new BigDecimal("50.00"), 1L);
        setAuthority(account, new BigDecimal("100.00"), 2L);
        mutateAuthorityAfterRead.set(true);

        CrossServerSyncManager manager = new CrossServerSyncManager(plugin, config("server-b"),
                redisManager, memory.facade(), cacheManager);
        invoke(manager, "performVersionCheck", Set.of(account));

        assertEquals(new BigDecimal("100.00"), memory.state(account).balance(),
                "a Redis mutation after the MGET must not pair a new balance with the old version");
        assertEquals(2L, memory.state(account).version());
        assertAuthority(account, new BigDecimal("130.00"), 3L);
    }

    @Test
    void crossServerReconcileNeverWritesLocalAheadStateBackToRedis() throws Exception {
        Syncmoney plugin = plugin();
        RedisManager redisManager = redisManager(null);
        CacheManager cacheManager = cacheManager(plugin, redisManager);
        UUID account = UUID.randomUUID();
        MemoryStateProbe memory = new MemoryStateProbe(cacheManager);
        memory.seed(account, new BigDecimal("120.00"), 9L);
        setAuthority(account, new BigDecimal("100.00"), 8L);

        CrossServerSyncManager manager = new CrossServerSyncManager(plugin, config("server-b"),
                redisManager, memory.facade(), cacheManager);
        invoke(manager, "performVersionCheck", Set.of(account));

        assertAuthority(account, new BigDecimal("100.00"), 8L);
        assertEquals(new BigDecimal("120.00"), memory.state(account).balance());
        assertEquals(9L, memory.state(account).version());
        assertEquals(0, memory.remoteUpdates());
    }

    @Test
    void bothReconcilersDeferAuthoritySnapshotWhileAcceptedWriteOwnsAccount() throws Exception {
        Syncmoney plugin = plugin();
        RedisManager redisManager = redisManager(null);
        CacheManager cacheManager = cacheManager(plugin, redisManager);
        UUID account = UUID.randomUUID();
        EconomyWriteQueue writeQueue = new EconomyWriteQueue(4, Logger.getLogger("RedisReconciliationIT"));
        EconomyFacade facade = new EconomyFacade(plugin, config("server-b"), cacheManager, redisManager,
                null, null, writeQueue, new FallbackEconomyWrapper(plugin, redisManager), null,
                mock(OverflowLogInterface.class));

        try {
            assertTrue(facade.updateMemoryState(account, new BigDecimal("10.00"), 1L));
            assertEquals(new BigDecimal("15.00"), facade.deposit(
                    account, new BigDecimal("5.00"), EconomyEvent.EventSource.TEST));
            assertTrue(writeQueue.hasPending(account), "the accepted write must remain queued during reconciliation");
            setAuthority(account, new BigDecimal("20.00"), 3L);

            SyncManager syncManager = new SyncManager(plugin, config("server-b"), cacheManager,
                    facade, redisManager);
            invoke(syncManager, "performVersionCheck");
            assertPendingProjectionIsPreserved(facade, account,
                    "SyncManager must defer a newer Redis snapshot while the accepted local write is pending");

            CrossServerSyncManager crossServerSyncManager = new CrossServerSyncManager(
                    plugin, config("server-b"), redisManager, facade, cacheManager);
            invoke(crossServerSyncManager, "performVersionCheck", Set.of(account));
            assertPendingProjectionIsPreserved(facade, account,
                    "CrossServerSyncManager must defer a newer Redis snapshot while the accepted local write is pending");
            assertAuthority(account, new BigDecimal("20.00"), 3L);
        } finally {
            facade.shutdown();
        }
    }

    @Test
    void droppedPubSubEventIsNotReplayedAfterReconnectAndReconciliationDoesNotEcho() throws Exception {
        UUID account = UUID.randomUUID();
        String accountId = account.toString();
        Logger subscriberLogger = Logger.getLogger("RedisReconciliationIT-" + accountId);
        subscriberLogger.setUseParentHandlers(false);
        subscriberLogger.setLevel(Level.FINE);
        AtomicInteger selfEchoLogs = new AtomicInteger();
        CountDownLatch barrierSelfEchoProcessed = new CountDownLatch(1);
        CountDownLatch controlSelfEchoProcessed = new CountDownLatch(1);
        CountDownLatch duplicateProcessed = new CountDownLatch(1);
        CountDownLatch delayedMessageProcessed = new CountDownLatch(1);
        Handler messageHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                String logMessage = record.getMessage();
                if (logMessage == null) return;
                if (logMessage.startsWith("Pub/Sub: ignored message from self (server-b)")) {
                    if (selfEchoLogs.incrementAndGet() == 1) {
                        barrierSelfEchoProcessed.countDown();
                    } else {
                        controlSelfEchoProcessed.countDown();
                    }
                } else if (logMessage.equals("Pub/Sub: rejected old version for " + accountId
                        + " (current=2 new=2)")) {
                    duplicateProcessed.countDown();
                } else if (logMessage.equals("Pub/Sub: rejected old version for " + accountId
                        + " (current=2 new=1)")) {
                    delayedMessageProcessed.countDown();
                }
            }

            @Override public void flush() { }
            @Override public void close() { }
        };
        subscriberLogger.addHandler(messageHandler);
        Syncmoney plugin = plugin(subscriberLogger);
        AtomicBoolean redisDegraded = new AtomicBoolean(true);
        RedisManager redisManager = redisManager(null);
        when(redisManager.isDegraded()).thenAnswer(ignored -> redisDegraded.get());
        CacheManager cacheManager = cacheManager(plugin, redisManager);
        MemoryStateProbe memory = new MemoryStateProbe(cacheManager);
        memory.seed(account, new BigDecimal("10.00"), 1L);

        AtomicInteger connectionAttempts = new AtomicInteger();
        AtomicReference<Long> firstSubscriberId = new AtomicReference<>();
        DebounceManager debounce = mock(DebounceManager.class);
        when(debounce.shouldProcess(any(UUID.class), anyLong(), anyString(), anyString())).thenReturn(true);
        SyncmoneyConfig subscriberConfig = config("server-b");
        when(subscriberConfig.isDebug()).thenReturn(true);
        PubsubSubscriber subscriber = new PubsubSubscriber(plugin, subscriberConfig, cacheManager,
                memory.facade(), debounce, redisManager, true, () -> {
                    if (connectionAttempts.incrementAndGet() == 1) {
                        throw new JedisConnectionException("simulated initial Redis outage");
                    }
                    Jedis client = RedisTestSupport.connect(redis);
                    firstSubscriberId.compareAndSet(null, client.clientId());
                    return client;
                });

        Jedis watcherConnection = null;
        Thread watcherThread = null;
        JedisPubSub watcher = null;
        AtomicBoolean watcherStopping = new AtomicBoolean();
        AtomicReference<Throwable> watcherFailure = new AtomicReference<>();
        try {
            setAuthority(account, new BigDecimal("10.00"), 1L);
            subscriber.startSubscription();
            redisDegraded.set(false);
            await(() -> subscriptionCount() == 1, "subscriber did not connect after the initial outage");
            assertTrue(connectionAttempts.get() >= 2, "the failed first connection should have been retried");

            try (Jedis killer = RedisTestSupport.connect(redis)) {
                assertEquals(1L, killer.clientKill(new ClientKillParams()
                        .id(String.valueOf(firstSubscriberId.get()))));
            }
            await(() -> subscriptionCount() == 0, "the killed subscription remained active");

            PubSubMessage missed = message(account, new BigDecimal("20.00"), 2L, "server-a", "missed-event");
            try (Jedis publisher = RedisTestSupport.connect(redis)) {
                publisher.mset(RedisKeys.balance(account), "20.00", RedisKeys.version(account), "2");
                assertEquals(0L, publisher.publish(RedisChannels.BALANCE_UPDATE, missed.toJson()),
                        "Redis Pub/Sub must not persist a message while this node is disconnected");
            }
            await(() -> subscriptionCount() == 1, "subscriber did not reconnect after its connection was killed");
            assertEquals(1L, memory.state(account).version(), "reconnect must not replay an at-most-once message");

            AtomicInteger observedPublishes = new AtomicInteger();
            CountDownLatch watcherSubscribed = new CountDownLatch(1);
            CountDownLatch reconciliationBarrierObserved = new CountDownLatch(1);
            String reconciliationBarrierPayload = message(account, new BigDecimal("20.00"), 3L,
                    "server-b", "reconciliation-barrier").toJson();
            watcher = new JedisPubSub() {
                @Override
                public void onSubscribe(String channel, int subscribedChannels) {
                    watcherSubscribed.countDown();
                }

                @Override
                public void onMessage(String channel, String payload) {
                    if (reconciliationBarrierPayload.equals(payload)) {
                        reconciliationBarrierObserved.countDown();
                    } else {
                        observedPublishes.incrementAndGet();
                    }
                }
            };
            watcherConnection = RedisTestSupport.connect(redis);
            Jedis finalWatcherConnection = watcherConnection;
            JedisPubSub finalWatcher = watcher;
            watcherThread = new Thread(() -> {
                try {
                    finalWatcherConnection.subscribe(finalWatcher, RedisChannels.BALANCE_UPDATE);
                } catch (RuntimeException failure) {
                    if (!watcherStopping.get()) {
                        watcherFailure.compareAndSet(null, failure);
                    }
                }
            }, "M10-PubSub-echo-watcher");
            watcherThread.setDaemon(true);
            watcherThread.start();
            assertTrue(watcherSubscribed.await(5, TimeUnit.SECONDS), "echo watcher did not subscribe");

            SyncManager manager = new SyncManager(plugin, config("server-b"), cacheManager,
                    memory.facade(), redisManager);
            invoke(manager, "performVersionCheck");

            assertEquals(new BigDecimal("20.00"), memory.state(account).balance());
            assertEquals(2L, memory.state(account).version());
            assertEquals(1, memory.remoteUpdates());
            try (Jedis publisher = RedisTestSupport.connect(redis)) {
                publisher.publish(RedisChannels.BALANCE_UPDATE, reconciliationBarrierPayload);
            }
            assertTrue(reconciliationBarrierObserved.await(5, TimeUnit.SECONDS),
                    "the Pub/Sub watcher must process the reconciliation barrier");
            assertTrue(barrierSelfEchoProcessed.await(5, TimeUnit.SECONDS),
                    "Syncmoney must process the ordered no-echo barrier before the assertion");
            assertEquals(0, observedPublishes.get(),
                    "reconciliation must not publish an echo before the ordered Pub/Sub barrier");

            PubSubMessage selfEcho = message(account, new BigDecimal("999.00"), 4L, "server-b", "self-echo");
            PubSubMessage duplicate = message(account, new BigDecimal("20.00"), 2L,
                    "server-a", "duplicate");
            PubSubMessage delayedOld = message(account, BigDecimal.ZERO, 1L,
                    "server-a", "delayed-old");
            try (Jedis publisher = RedisTestSupport.connect(redis)) {
                publisher.publish(RedisChannels.BALANCE_UPDATE, selfEcho.toJson());
                publisher.publish(RedisChannels.BALANCE_UPDATE, duplicate.toJson());
                publisher.publish(RedisChannels.BALANCE_UPDATE, delayedOld.toJson());
            }
            assertTrue(controlSelfEchoProcessed.await(5, TimeUnit.SECONDS),
                    "Syncmoney must process the self-echo control message");
            assertTrue(duplicateProcessed.await(5, TimeUnit.SECONDS),
                    "Syncmoney must reject the duplicate through its live Pub/Sub callback");
            assertTrue(delayedMessageProcessed.await(5, TimeUnit.SECONDS),
                    "Syncmoney must reject the delayed message through its live Pub/Sub callback");
            assertEquals(new BigDecimal("20.00"), memory.state(account).balance());
            assertEquals(2L, memory.state(account).version());
            assertEquals(1, memory.remoteUpdates(), "self, duplicate, and delayed messages must not reapply state");
        } finally {
            try {
                subscriber.stopSubscription();
            } finally {
                try {
                    if (watcher != null) {
                        watcher.unsubscribe(RedisChannels.BALANCE_UPDATE);
                    }
                    if (watcherThread != null) {
                        watcherThread.join(TimeUnit.SECONDS.toMillis(5));
                    }
                } finally {
                    watcherStopping.set(true);
                    if (watcherConnection != null) {
                        watcherConnection.close();
                    }
                    if (watcherThread != null && watcherThread.isAlive()) {
                        watcherThread.join(TimeUnit.SECONDS.toMillis(5));
                    }
                }
            }
            if (watcherThread != null) {
                assertFalse(watcherThread.isAlive(), "echo watcher must stop after unsubscribe and connection cleanup");
            }
            assertNull(watcherFailure.get(), "echo watcher must not fail while observing the test channel");
            subscriberLogger.removeHandler(messageHandler);
        }
    }

    private RedisManager redisManager(AtomicBoolean mutateAfterNextMget) {
        return redisManager(mutateAfterNextMget, null);
    }

    private RedisManager redisManager(AtomicBoolean mutateAfterNextMget, UUID accountForMgetRace) {
        RedisManager manager = mock(RedisManager.class);
        when(manager.isDegraded()).thenReturn(false);
        when(manager.getResource()).thenAnswer(ignored -> {
            Jedis connection = RedisTestSupport.connect(redis);
            if (mutateAfterNextMget == null) {
                return connection;
            }

            Jedis observed = spy(connection);
            doAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                List<String> result = (List<String>) invocation.callRealMethod();
                if (mutateAfterNextMget.compareAndSet(true, false)) {
                    try (Jedis writer = RedisTestSupport.connect(redis)) {
                        writer.mset(RedisKeys.balance(accountForMgetRace), "130.00",
                                RedisKeys.version(accountForMgetRace), "3");
                    }
                }
                return result;
            }).when(observed).mget(any(String[].class));
            return observed;
        });
        return manager;
    }

    private CacheManager cacheManager(Syncmoney plugin, RedisManager redisManager) {
        CacheManager cacheManager = new CacheManager(plugin, redisManager, true);
        cacheManagers.add(cacheManager);
        return cacheManager;
    }

    private void setAuthority(UUID account, BigDecimal balance, long version) {
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            jedis.mset(RedisKeys.balance(account), balance.toPlainString(),
                    RedisKeys.version(account), Long.toString(version));
        }
    }

    private void assertAuthority(UUID account, BigDecimal balance, long version) {
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            List<String> pair = jedis.mget(RedisKeys.balance(account), RedisKeys.version(account));
            assertEquals(balance.toPlainString(), pair.get(0));
            assertEquals(Long.toString(version), pair.get(1));
        }
    }

    private long subscriptionCount() {
        try (Jedis jedis = RedisTestSupport.connect(redis)) {
            return jedis.pubsubNumSub(RedisChannels.BALANCE_UPDATE).get(RedisChannels.BALANCE_UPDATE);
        } catch (Exception unavailable) {
            return -1;
        }
    }

    private static Syncmoney plugin() {
        return plugin(Logger.getLogger("RedisReconciliationIT"));
    }

    private static Syncmoney plugin(Logger logger) {
        Syncmoney plugin = mock(Syncmoney.class, RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getResource(anyString())).thenAnswer(invocation ->
                CacheManager.class.getClassLoader().getResourceAsStream(invocation.getArgument(0)));
        return plugin;
    }

    private static SyncmoneyConfig config(String serverName) {
        SyncmoneyConfig config = mock(SyncmoneyConfig.class, RETURNS_DEEP_STUBS);
        when(config.getServerName()).thenReturn(serverName);
        when(config.isPubsubEnabled()).thenReturn(true);
        return config;
    }

    private static PubSubMessage message(UUID account, BigDecimal balance, long version,
                                         String sourceServer, String messageId) {
        return new PubSubMessage(account.toString(), balance, version, sourceServer,
                messageId, System.currentTimeMillis(), "DEPOSIT", 10.0, "Syncmoney", null);
    }

    private static void invoke(Object target, String methodName, Object... arguments) throws Exception {
        Class<?>[] parameterTypes = methodName.equals("performVersionCheck") && arguments.length == 1
                ? new Class<?>[] {Set.class}
                : new Class<?>[0];
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        method.invoke(target, arguments);
    }

    private static void assertPendingProjectionIsPreserved(EconomyFacade facade, UUID account, String message) {
        EconomyFacade.EconomyState state = facade.getMemoryState(account);
        assertEquals(new BigDecimal("15.00"), state.balance(), message);
        assertEquals(2L, state.version(), message);
    }

    private static void await(BooleanSupplier condition, String failureMessage) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), failureMessage);
    }

    private static final class MemoryStateProbe {
        private final Map<UUID, EconomyFacade.EconomyState> states = new ConcurrentHashMap<>();
        private final AtomicInteger remoteUpdates = new AtomicInteger();
        private final EconomyFacade facade = mock(EconomyFacade.class);
        private final CacheManager cacheManager;

        private MemoryStateProbe(CacheManager cacheManager) {
            this.cacheManager = cacheManager;
            when(facade.getOnlinePlayerUuids()).thenAnswer(ignored -> Set.copyOf(states.keySet()));
            when(facade.getMemoryState(any(UUID.class))).thenAnswer(invocation ->
                    states.get(invocation.getArgument(0)));
            when(facade.getBalance(any(UUID.class))).thenAnswer(invocation -> {
                EconomyFacade.EconomyState state = states.get(invocation.getArgument(0));
                return state == null ? BigDecimal.ZERO : state.balance();
            });
            when(facade.updateMemoryState(any(UUID.class), any(BigDecimal.class), anyLong()))
                    .thenAnswer(invocation -> {
                        UUID account = invocation.getArgument(0);
                        BigDecimal balance = invocation.getArgument(1);
                        long version = invocation.getArgument(2);
                        EconomyFacade.EconomyState current = states.get(account);
                        if (current != null && version <= current.version()) {
                            return false;
                        }
                        states.put(account, new EconomyFacade.EconomyState(balance, version,
                                System.currentTimeMillis()));
                        cacheManager.updateMemoryCache(account, balance, version);
                        remoteUpdates.incrementAndGet();
                        return true;
                    });
        }

        private void seed(UUID account, BigDecimal balance, long version) {
            states.put(account, new EconomyFacade.EconomyState(balance, version, System.currentTimeMillis()));
            cacheManager.updateMemoryCache(account, balance, version);
        }

        private EconomyFacade facade() {
            return facade;
        }

        private EconomyFacade.EconomyState state(UUID account) {
            return states.get(account);
        }

        private int remoteUpdates() {
            return remoteUpdates.get();
        }
    }
}
