package noietime.syncmoney.sync;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.protocol.RedisChannels;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PubsubSubscriberModeRoutingTest {

    @Test
    void cmiModeSubscribesOnlyToTheCmiAuthorityChannel() {
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isCMIMode()).thenReturn(true);
        PubsubSubscriber subscriber = subscriber(config);

        assertArrayEquals(new String[] { RedisChannels.CMI_BALANCE_UPDATE }, subscriber.subscriptionChannels());
    }

    @Test
    void syncmoneyManagedModesSubscribeOnlyToTheGenericBalanceChannel() {
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isCMIMode()).thenReturn(false);
        PubsubSubscriber subscriber = subscriber(config);

        assertArrayEquals(new String[] { RedisChannels.BALANCE_UPDATE }, subscriber.subscriptionChannels());
    }

    private static PubsubSubscriber subscriber(SyncmoneyConfig config) {
        return new PubsubSubscriber(mock(Plugin.class), config, mock(CacheManager.class),
                mock(EconomyFacade.class), mock(DebounceManager.class), mock(RedisManager.class), true);
    }
}
