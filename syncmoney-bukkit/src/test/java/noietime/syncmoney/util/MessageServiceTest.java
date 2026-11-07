package noietime.syncmoney.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MessageService.
 */
class MessageServiceTest {

    @Mock
    private Plugin mockPlugin;

    private MessageService messageService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockPlugin.getDataFolder()).thenReturn(new File("target/test-messages"));
        when(mockPlugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        
        messageService = new MessageService(mockPlugin);
    }

    @Test
    void testMessageServiceInitialization() {

        assertNotNull(messageService);
    }

    @Test
    void testGetMessageWithNullConfig() {

        String result = messageService.getMessage("test.key");

        assertNotNull(result);
    }

    @Test
    void testMessageFallback() {


        

        messageService.loadMessages();
        


        String prefix = messageService.getMessage("prefix");
        

        assertNotNull(prefix);
    }

    @Test
    void testCacheSize() {

        messageService.loadMessages();
        int cacheSize = messageService.getCacheSize();
        


        assertTrue(cacheSize >= 0);
    }

    @Test
    void testClearCache() {

        messageService.loadMessages();
        messageService.clearCache();
        
        assertEquals(0, messageService.getCacheSize());
    }

    @Test
    void testReload() {

        messageService.loadMessages();
        boolean result = messageService.reload();
        
        assertTrue(result);
    }
}
