package noietime.syncmoney.util;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageHelperCompatibilityTest {

    @Test
    void preservesFormattingWhileRemovingInteractiveTags() {
        Component component = MessageHelper.parseMessage(
                "<click:run_command:'/syncmoney version'><red>Report</red></click>");

        assertNull(component.clickEvent());
        assertTrue(MessageHelper.toLegacyString(component).contains("&cReport"));
    }
}
