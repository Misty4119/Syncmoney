package noietime.syncmoney.config;

import noietime.syncmoney.Syncmoney;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ConfigManager#validate(String, String, Object)}.
 *
 * Validation is driven by {@link ConfigFieldMetadata}, so these tests exercise
 * type checks, min/max bounds, allowed-value lists, editability and the
 * permissive fallback for fields without metadata.
 */
class ConfigManagerValidateTest {

    private ConfigManager configManager;

    @BeforeEach
    void setUp(@TempDir File tempDir) {
        Syncmoney plugin = mock(Syncmoney.class);
        when(plugin.getDataFolder()).thenReturn(tempDir);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        configManager = new ConfigManager(plugin, config);
    }

    @Test
    void nullValueIsRejected() {
        assertFalse(configManager.validate("economy", "mode", null).valid());
    }

    @Test
    void allowedStringValueIsAccepted() {
        assertTrue(configManager.validate("economy", "mode", "sync").valid());
    }

    @Test
    void disallowedStringValueIsRejected() {
        ConfigManager.ValidationResult result = configManager.validate("economy", "mode", "not-a-mode");
        assertFalse(result.valid());
        assertTrue(result.message().contains("one of"));
    }

    @Test
    void numberWithinBoundsIsAccepted() {
        assertTrue(configManager.validate("circuit-breaker", "max-transactions-per-second", 10).valid());
    }

    @Test
    void numberBelowMinIsRejected() {
        ConfigManager.ValidationResult result =
                configManager.validate("circuit-breaker", "max-transactions-per-second", 0);
        assertFalse(result.valid());
        assertTrue(result.message().contains(">="));
    }

    @Test
    void numberAboveMaxIsRejected() {
        ConfigManager.ValidationResult result =
                configManager.validate("circuit-breaker", "max-transactions-per-second", 999);
        assertFalse(result.valid());
        assertTrue(result.message().contains("<="));
    }

    @Test
    void wrongTypeForNumberFieldIsRejected() {
        assertFalse(configManager.validate("circuit-breaker", "max-transactions-per-second", "ten").valid());
    }

    @Test
    void booleanFieldAcceptsBooleanRejectsOther() {
        assertTrue(configManager.validate("circuit-breaker", "enabled", true).valid());
        assertFalse(configManager.validate("circuit-breaker", "enabled", "yes").valid());
    }

    @Test
    void nonEditableFieldIsRejected() {
        ConfigManager.ValidationResult result = configManager.validate("core", "server-name", "anything");
        assertFalse(result.valid());
        assertTrue(result.message().contains("not editable"));
    }

    @Test
    void unknownFieldFallsBackToPermissiveTypeCheck() {
        assertTrue(configManager.validate("totally", "unknown", "value").valid());
        assertTrue(configManager.validate("totally", "unknown", 123).valid());
    }
}
