package noietime.syncmoney.vault;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultRuntimeDetectorTest {

    @Test
    void hasVault2_detectsVaultUnlockedApi() {
        assertTrue(VaultRuntimeDetector.hasVault2(getClass().getClassLoader()));
    }

    @Test
    void hasVault2_returnsFalseWhenModernApiIsHidden() {
        ClassLoader legacyOnly = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (VaultRuntimeDetector.VAULT2_ECONOMY_CLASS.equals(name)) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };

        assertFalse(VaultRuntimeDetector.hasVault2(legacyOnly));
    }
}
