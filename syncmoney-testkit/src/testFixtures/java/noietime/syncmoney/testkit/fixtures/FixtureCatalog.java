package noietime.syncmoney.testkit.fixtures;

import noietime.syncmoney.core.port.ConfirmedAccount;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Synthetic, credential-free compatibility fixtures derived from the 1.3.2 source contract. */
public final class FixtureCatalog {

    public static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private FixtureCatalog() { }

    public static List<ConfirmedAccount> transferStartingAccounts() {
        return List.of(new ConfirmedAccount(PLAYER_A, new BigDecimal("100.00"), 1),
                new ConfirmedAccount(PLAYER_B, new BigDecimal("0.00"), 1));
    }

    public static String legacyBalanceV1Json() {
        return read("/protocol/legacy-balance-v1.json");
    }

    public static String legacyCmiV1Json() {
        return read("/protocol/legacy-cmi-v1.json");
    }

    public static String legacyLocalSqliteV132Sql() {
        return read("/sql/legacy-local-sqlite-v1.3.2.sql");
    }

    public static String read(String resourcePath) {
        try (InputStream input = FixtureCatalog.class.getResourceAsStream(resourcePath)) {
            if (input == null) throw new IllegalStateException("Missing shared test fixture: " + resourcePath);
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read shared test fixture: " + resourcePath, exception);
        }
    }
}
