package noietime.syncmoney.sync;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PubsubSubscriberProtocolCompatibilityTest {

    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final long LEGACY_VERSION = 9_007_199_254_740_993L;

    @Test
    void legacyJsonWithUnknownFieldsAppliesOnceAndDuplicateDoesNotApplyAgain() throws Exception {
        Plugin plugin = plugin();
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.getServerName()).thenReturn("local-node");
        CacheManager cache = mock(CacheManager.class);
        when(cache.getVersion(ACCOUNT)).thenReturn(7L);
        EconomyFacade economy = mock(EconomyFacade.class);
        when(economy.updateMemoryState(eq(ACCOUNT), any(BigDecimal.class), eq(LEGACY_VERSION)))
                .thenReturn(true);
        DebounceManager debounce = new DebounceManager(plugin, config);
        PubsubSubscriber subscriber = subscriber(plugin, config, cache, economy, debounce);
        activateInboundHandler(subscriber);

        String oldPayload = "{\"uuid\":\"" + ACCOUNT + "\",\"balance\":12.34,"
                + "\"version\":" + LEGACY_VERSION + ",\"sourceServer\":null,"
                + "\"messageId\":\"legacy-message\",\"eventType\":\"DEPOSIT\","
                + "\"futureField\":{\"enabled\":true}}";

        invokeMessageHandler(subscriber, oldPayload);
        invokeMessageHandler(subscriber, oldPayload);

        verify(economy, times(1)).updateMemoryState(
                ACCOUNT, new BigDecimal("12.34"), LEGACY_VERSION);
        verify(cache, times(1)).updateMemoryCache(
                ACCOUNT, new BigDecimal("12.34"), LEGACY_VERSION);
    }

    @Test
    void staleLegacyJsonDoesNotReachDebounceOrMemoryAndCacheApply() throws Exception {
        Plugin plugin = plugin();
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.getServerName()).thenReturn("local-node");
        CacheManager cache = mock(CacheManager.class);
        when(cache.getVersion(ACCOUNT)).thenReturn(LEGACY_VERSION);
        EconomyFacade economy = mock(EconomyFacade.class);
        DebounceManager debounce = mock(DebounceManager.class);
        PubsubSubscriber subscriber = subscriber(plugin, config, cache, economy, debounce);
        activateInboundHandler(subscriber);

        String stalePayload = "{\"uuid\":\"" + ACCOUNT + "\",\"balance\":10.00,"
                + "\"version\":" + (LEGACY_VERSION - 1) + ",\"sourceServer\":null,"
                + "\"messageId\":\"stale-message\",\"unknownLegacyField\":\"ignored\"}";

        invokeMessageHandler(subscriber, stalePayload);

        verify(cache, times(1)).getVersion(ACCOUNT);
        verify(debounce, never()).shouldProcess(any(), org.mockito.ArgumentMatchers.anyLong(),
                any(), any());
        verify(economy, never()).updateMemoryState(any(), any(BigDecimal.class),
                org.mockito.ArgumentMatchers.anyLong());
        verify(cache, never()).updateMemoryCache(any(), any(BigDecimal.class),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private static Plugin plugin() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger(
                PubsubSubscriberProtocolCompatibilityTest.class.getName()));
        return plugin;
    }

    private static PubsubSubscriber subscriber(Plugin plugin, SyncmoneyConfig config,
                                               CacheManager cache, EconomyFacade economy,
                                               DebounceManager debounce) {
        return new PubsubSubscriber(plugin, config, cache, economy, debounce,
                mock(RedisManager.class), false);
    }

    private static void activateInboundHandler(PubsubSubscriber subscriber) throws Exception {
        Field runningField = PubsubSubscriber.class.getDeclaredField("running");
        runningField.setAccessible(true);
        ((AtomicBoolean) runningField.get(subscriber)).set(true);
    }

    private static void invokeMessageHandler(PubsubSubscriber subscriber, String payload)
            throws Exception {
        Method handler = PubsubSubscriber.class.getDeclaredMethod("handleMessage", String.class);
        handler.setAccessible(true);
        handler.invoke(subscriber, payload);
    }
}
