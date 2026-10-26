package noietime.syncmoney.protocol;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Canonical payload fingerprints shared by Redis mutations and recovery reconciliation. */
public final class RedisOperationFingerprint {

    private RedisOperationFingerprint() { }

    public static String add(UUID account, BigDecimal amount) {
        return sha256("syncmoney-operation-v1|ADD|" + Objects.requireNonNull(account, "account")
                + "|" + canonicalMoney(amount));
    }

    public static String set(UUID account, BigDecimal balance) {
        return sha256("syncmoney-operation-v1|SET|" + Objects.requireNonNull(account, "account")
                + "|" + canonicalMoney(balance));
    }

    public static String transfer(UUID from, UUID to, BigDecimal amount) {
        return sha256("syncmoney-operation-v1|TRANSFER|" + Objects.requireNonNull(from, "from")
                + "|" + Objects.requireNonNull(to, "to") + "|" + canonicalMoney(amount));
    }

    public static String sha256(String value) {
        Objects.requireNonNull(value, "value");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static String canonicalMoney(BigDecimal amount) {
        return Objects.requireNonNull(amount, "amount").setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
