package noietime.syncmoney.web.nodes;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.web.api.nodes.NodesApiHandler;
import noietime.syncmoney.web.websocket.SseManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NodeHealthCheckerTest {

    @Test
    void failedHealthCheckDoesNotExposeConfiguredUrlInLogsOrStatusEvents() {
        String userInfo = "health-user:health-password";
        String configuredUrl = "https://" + userInfo + "@node.example/%zz";
        Logger logger = Logger.getLogger("node-health-checker-test-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);

        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getLogger()).thenReturn(logger);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        when(config.isCentralMode()).thenReturn(true);
        SyncmoneyConfig.NodeConfig node = new SyncmoneyConfig.NodeConfig();
        node.name = "remote-node";
        node.url = configuredUrl;
        node.enabled = true;
        when(config.getNodes()).thenReturn(List.of(node));

        SseManager sseManager = mock(SseManager.class);
        NodeHealthChecker checker = new NodeHealthChecker(
                plugin, config, null, sseManager, mock(NodesApiHandler.class));
        checker.run();

        String logText = records.stream()
                .map(LogRecord::getMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertFalse(logText.contains(userInfo));
        assertFalse(logText.contains(configuredUrl));

        ArgumentCaptor<String> event = ArgumentCaptor.forClass(String.class);
        verify(sseManager).broadcastToChannel(eq("system"), event.capture());
        assertTrue(event.getValue().contains("[invalid URL]"));
        assertFalse(event.getValue().contains(userInfo));
        assertFalse(event.getValue().contains(configuredUrl));
    }
}
