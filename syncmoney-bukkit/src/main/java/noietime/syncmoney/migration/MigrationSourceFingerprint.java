package noietime.syncmoney.migration;

import noietime.syncmoney.migration.CMIDatabaseReader.CMIPlayerData;

import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Builds a stable digest of the fields that determine CMI migration output. */
final class MigrationSourceFingerprint {

    private static final byte[] FORMAT_MARKER = "syncmoney-cmi-source-v1".getBytes(StandardCharsets.UTF_8);

    record Result(int playerCount, String value) {}

    private MigrationSourceFingerprint() {}

    static MessageDigest newDigest() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateField(digest, FORMAT_MARKER);
            return digest;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static void update(MessageDigest digest, CMIPlayerData player) {
        updateField(digest, player.playerName());
        updateNullableField(digest, player.uuid() == null ? null : player.uuid().toString());
        updateField(digest, player.balance().setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    static Result finish(MessageDigest digest, int playerCount) {
        return new Result(playerCount, HexFormat.of().formatHex(digest.digest()));
    }

    static Result fingerprint(List<CMIPlayerData> players) {
        List<CMIPlayerData> ordered = orderedPlayers(players);

        MessageDigest digest = newDigest();
        ordered.forEach(player -> update(digest, player));
        return finish(digest, ordered.size());
    }

    static List<CMIPlayerData> orderedPlayers(List<CMIPlayerData> players) {
        List<CMIPlayerData> ordered = new ArrayList<>(players);
        ordered.sort(Comparator
                .comparing((CMIPlayerData player) -> player.playerName().toLowerCase(Locale.ROOT))
                .thenComparing(CMIPlayerData::playerName)
                .thenComparing(player -> player.uuid() == null ? "" : player.uuid().toString())
                .thenComparing(player -> player.balance().setScale(2, RoundingMode.HALF_UP).toPlainString()));
        return ordered;
    }

    private static void updateField(MessageDigest digest, String value) {
        updateField(digest, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void updateNullableField(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        updateField(digest, value);
    }

    private static void updateField(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }
}
