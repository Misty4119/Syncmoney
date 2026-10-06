package noietime.syncmoney.economy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Checksummed atomic handoff for accepted LOCAL writes that could not be
 * drained before plugin shutdown. SQLite request-id deduplication makes replay
 * safe when a commit succeeded but its receipt did not reach this process.
 */
final class LocalPersistenceJournal {

    private static final String FORMAT = "SYNCLOCAL1";
    private static final String FILE_NAME = "local-persistence.journal";

    record Entry(EconomyEvent event, String playerName) {
        Entry {
            Objects.requireNonNull(event, "event");
            if (event.playerUuid() == null || event.requestId() == null || event.requestId().isBlank()) {
                throw new IllegalArgumentException("Journaled LOCAL writes require an account and request id");
            }
            playerName = playerName == null || playerName.isBlank()
                    ? event.playerUuid().toString() : playerName;
        }
    }

    private final Path file;

    LocalPersistenceJournal(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    static Path pathForDatabase(String databasePath) {
        return Path.of(databasePath).toAbsolutePath().resolveSibling(FILE_NAME);
    }

    static boolean existsForDatabase(String databasePath) {
        if (databasePath == null || databasePath.isBlank()) {
            return true;
        }
        try {
            Path journal = pathForDatabase(databasePath);
            BasicFileAttributes attributes = Files.readAttributes(
                    journal, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                return true;
            }
            try (FileChannel channel = FileChannel.open(journal,
                    StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                return channel.size() != 0;
            }
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException | SecurityException e) {
            // A caller that cannot verify the journal state must not snapshot LOCAL data.
            return true;
        } catch (RuntimeException e) {
            // Invalid configured paths also fail closed for migration readiness.
            return true;
        }
    }

    synchronized void append(Collection<Entry> additions) throws IOException {
        if (additions == null || additions.isEmpty()) {
            return;
        }

        Map<String, Entry> known = new HashMap<>();
        for (Entry entry : readPending()) {
            known.put(entry.event().requestId(), entry);
        }

        for (Entry entry : additions) {
            Entry existing = known.get(entry.event().requestId());
            if (existing != null) {
                if (!existing.event().equals(entry.event())) {
                    throw new IOException("Conflicting LOCAL journal payload for request "
                            + entry.event().requestId());
                }
                continue;
            }
            known.put(entry.event().requestId(), entry);
        }
        if (known.isEmpty()) {
            return;
        }

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            List<Entry> records = known.values().stream()
                    .sorted(Comparator.comparing((Entry entry) -> entry.event().playerUuid().toString())
                            .thenComparingLong(entry -> entry.event().version())
                            .thenComparingLong(entry -> entry.event().timestamp()))
                    .toList();
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                for (Entry entry : records) {
                    ByteBuffer bytes = StandardCharsets.UTF_8.encode(encode(entry) + "\n");
                    while (bytes.hasRemaining()) {
                        channel.write(bytes);
                    }
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                throw new IOException("Filesystem does not support atomic LOCAL journal replacement", e);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    synchronized List<Entry> readPending() throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        byte[] contents = Files.readAllBytes(file);
        if (contents.length == 0) {
            return List.of();
        }

        String text = new String(contents, StandardCharsets.UTF_8);
        String[] lines = text.split("\\R", -1);
        Map<String, Entry> unique = new HashMap<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty() && i == lines.length - 1) {
                continue;
            }
            Entry entry;
            try {
                entry = decode(line);
            } catch (RuntimeException e) {
                throw new IOException("Invalid LOCAL persistence journal record at line " + (i + 1), e);
            }
            Entry previous = unique.putIfAbsent(entry.event().requestId(), entry);
            if (previous != null && !previous.event().equals(entry.event())) {
                throw new IOException("Conflicting LOCAL journal records for request "
                        + entry.event().requestId());
            }
        }
        return unique.values().stream()
                .sorted(Comparator.comparing((Entry entry) -> entry.event().playerUuid().toString())
                        .thenComparingLong(entry -> entry.event().version())
                        .thenComparingLong(entry -> entry.event().timestamp()))
                .toList();
    }

    synchronized void clear() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.force(true);
        }
    }

    private static String encode(Entry entry) {
        EconomyEvent event = entry.event();
        String payload = String.join("\t",
                FORMAT,
                b64(event.playerUuid().toString()),
                b64(event.delta().toPlainString()),
                b64(event.balanceAfter().toPlainString()),
                Long.toString(event.version()),
                event.type().name(),
                event.source().name(),
                b64(event.requestId()),
                Long.toString(event.timestamp()),
                b64(entry.playerName()));
        return payload + "\t" + checksum(payload);
    }

    private static Entry decode(String line) {
        String[] parts = line.split("\\t", -1);
        if (parts.length != 11 || !FORMAT.equals(parts[0])) {
            throw new IllegalArgumentException("Unsupported record framing");
        }
        String payload = String.join("\t", List.of(parts).subList(0, 10));
        if (!MessageDigest.isEqual(checksum(payload).getBytes(StandardCharsets.US_ASCII),
                parts[10].getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Checksum mismatch");
        }
        UUID uuid = UUID.fromString(unb64(parts[1]));
        EconomyEvent event = new EconomyEvent(uuid,
                new java.math.BigDecimal(unb64(parts[2])),
                new java.math.BigDecimal(unb64(parts[3])),
                Long.parseLong(parts[4]),
                EconomyEvent.EventType.valueOf(parts[5]),
                EconomyEvent.EventSource.valueOf(parts[6]),
                unb64(parts[7]),
                Long.parseLong(parts[8]));
        return new Entry(event, unb64(parts[9]));
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String checksum(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }
}
