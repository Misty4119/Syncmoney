package noietime.syncmoney.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CrossServerSyncManagerNotificationTest {

    @Test
    void knownEventUsesTheProtocolMapping() {
        assertEquals("cross-server.money-received",
                CrossServerSyncManager.notificationMessageKey("VAULT_DEPOSIT", -1.0));
        assertEquals("admin.money-taken",
                CrossServerSyncManager.notificationMessageKey("ADMIN_TAKE", 1.0));
    }

    @Test
    void unknownEventsRetainTheAmountDirectionFallback() {
        assertEquals("cross-server.money-received",
                CrossServerSyncManager.notificationMessageKey("FUTURE_EVENT", 1.0));
        assertEquals("cross-server.money-spent",
                CrossServerSyncManager.notificationMessageKey("FUTURE_EVENT", -1.0));
        assertEquals("cross-server.money-spent",
                CrossServerSyncManager.notificationMessageKey(null, 0.0));
    }
}
