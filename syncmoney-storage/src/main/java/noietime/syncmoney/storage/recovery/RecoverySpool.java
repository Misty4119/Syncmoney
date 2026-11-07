package noietime.syncmoney.storage.recovery;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Checksummed append-only checkpoint journal for internal accepted-write recovery. */
public final class RecoverySpool {
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;
    private static final long DEFAULT_MAX_JOURNAL_BYTES = 64L * 1024 * 1024;
    private static final IoHooks NO_HOOKS = new IoHooks() { };

    public record Entry(String id, String payload) { }

    private record Frame(int version, long sequence, String kind, String id, String payload, String checksum) { }

    /** Package-private fault seam used by deterministic file-failure tests. */
    interface IoHooks {
        default void beforeAppend(Path journal, byte[] bytes) throws IOException { }
        default void beforeAtomicReplace(Path temporary, Path target) throws IOException { }
    }

    private final Path journal;
    private final IoHooks ioHooks;
    private final long maxJournalBytes;
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());
    private final Map<String, Entry> pending = new LinkedHashMap<>();
    private boolean loaded;
    private IOException writeFailure;
    private long sequence;

    public RecoverySpool(Path journal) {
        this(journal, NO_HOOKS, DEFAULT_MAX_JOURNAL_BYTES);
    }

    RecoverySpool(Path journal, IoHooks ioHooks) {
        this(journal, ioHooks, DEFAULT_MAX_JOURNAL_BYTES);
    }

    RecoverySpool(Path journal, IoHooks ioHooks, long maxJournalBytes) {
        this.journal = Objects.requireNonNull(journal, "journal").toAbsolutePath().normalize();
        this.ioHooks = Objects.requireNonNull(ioHooks, "ioHooks");
        if (maxJournalBytes <= 0) throw new IllegalArgumentException("maxJournalBytes must be positive");
        this.maxJournalBytes = maxJournalBytes;
    }

    /** Appends or checkpoints one operation. A repeated identical payload is a no-op. */
    public synchronized void append(String id, String payload) throws IOException {
        validateRecord(id, payload);
        ensureLoaded();
        ensureWritable();
        Entry existing = pending.get(id);
        if (existing != null && existing.payload().equals(payload)) return;

        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        appendFrame("RECORD", id, encodedPayload);
        pending.put(id, new Entry(id, payload));
    }

    /** Reads pending entries without changing the journal. */
    public synchronized List<Entry> pending() throws IOException {
        ensureLoaded();
        return List.copyOf(pending.values());
    }

    /** Adds a durable tombstone; an operation is removed only after this frame is forced. */
    public synchronized void acknowledge(String id) throws IOException {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        ensureLoaded();
        ensureWritable();
        if (!pending.containsKey(id)) return;

        appendFrame("ACK", id, "");
        pending.remove(id);
    }

    /** Rewrites only current pending entries; the original remains intact until atomic replace. */
    public synchronized void compact() throws IOException {
        ensureLoaded();
        ensureWritable();
        Path parent = journal.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = journal.resolveSibling(journal.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                long next = 0;
                long written = 0;
                for (Entry entry : pending.values()) {
                    String encoded = Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(entry.payload().getBytes(StandardCharsets.UTF_8));
                    byte[] bytes = encode(frame("RECORD", entry.id(), encoded, next + 1));
                    if (written + bytes.length > maxJournalBytes) {
                        throw new IOException("Pending recovery records exceed the bounded journal capacity");
                    }
                    next++;
                    writeFully(channel, ByteBuffer.wrap(bytes));
                    written += bytes.length;
                }
                channel.force(true);
            }
            ioHooks.beforeAtomicReplace(temporary, journal);
            try {
                Files.move(temporary, journal, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Atomic recovery journal replacement is not supported", unsupported);
            }
            forceDirectory(parent);
            sequence = pending.size();
        } catch (IOException failure) {
            throw failure;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void ensureLoaded() throws IOException {
        if (loaded) return;
        if (!Files.exists(journal)) {
            loaded = true;
            return;
        }
        if (Files.size(journal) > maxJournalBytes) {
            throw new IOException("Recovery journal exceeds the bounded size limit; original bytes were preserved");
        }
        byte[] bytes = Files.readAllBytes(journal);
        if (bytes.length > 0 && bytes[bytes.length - 1] != '\n') {
            throw new IOException("Recovery journal contains a partial final line; original bytes were preserved");
        }
        String contents = decodeUtf8(bytes);
        String[] lines = contents.split("\n", -1);
        long previousSequence = 0;
        Map<String, Entry> restored = new LinkedHashMap<>();
        for (int index = 0; index < lines.length - 1; index++) {
            String line = lines[index];
            if (line.isEmpty()) throw corrupt(index + 1, "empty frame");
            if (line.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) {
                throw corrupt(index + 1, "frame exceeds maximum size");
            }
            Frame frame;
            try {
                frame = mapper.readValue(line, Frame.class);
            } catch (Exception malformed) {
                throw corrupt(index + 1, "invalid JSON frame", malformed);
            }
            if (frame.version() != FORMAT_VERSION || frame.sequence() <= previousSequence
                    || frame.id() == null || frame.id().isBlank() || frame.payload() == null
                    || !constantTimeEquals(frame.checksum(), checksum(frame.version(), frame.sequence(),
                            frame.kind(), frame.id(), frame.payload()))) {
                throw corrupt(index + 1, "version, sequence, identity, or checksum mismatch");
            }
            previousSequence = frame.sequence();
            if ("RECORD".equals(frame.kind())) {
                if (frame.payload().length() > MAX_FRAME_BYTES) throw corrupt(index + 1, "payload exceeds maximum size");
                byte[] payload;
                try {
                    payload = Base64.getUrlDecoder().decode(frame.payload());
                } catch (IllegalArgumentException malformed) {
                    throw corrupt(index + 1, "invalid payload encoding", malformed);
                }
                restored.put(frame.id(), new Entry(frame.id(), decodeUtf8(payload)));
            } else if ("ACK".equals(frame.kind()) && frame.payload().isEmpty()) {
                restored.remove(frame.id());
            } else {
                throw corrupt(index + 1, "unknown frame kind");
            }
        }
        pending.putAll(restored);
        sequence = previousSequence;
        loaded = true;
    }

    private void appendFrame(String kind, String id, String payload) throws IOException {
        ensureWritable();
        Frame frame = frame(kind, id, payload, sequence + 1);
        byte[] bytes = encode(frame);
        if (journalSize() + bytes.length > maxJournalBytes) {
            compact();
            frame = frame(kind, id, payload, sequence + 1);
            bytes = encode(frame);
        }
        if (journalSize() + bytes.length > maxJournalBytes) {
            throw new IOException("Recovery journal is full; existing pending records were preserved");
        }
        try {
            Path parent = journal.getParent();
            if (parent != null) Files.createDirectories(parent);
            ioHooks.beforeAppend(journal, bytes);
            try (FileChannel channel = FileChannel.open(journal,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                writeFully(channel, ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            sequence = frame.sequence();
        } catch (IOException failure) {
            writeFailure = failure;
            throw failure;
        }
    }

    private long journalSize() throws IOException {
        return Files.exists(journal) ? Files.size(journal) : 0L;
    }

    private Frame frame(String kind, String id, String payload, long sequence) {
        return new Frame(FORMAT_VERSION, sequence, kind, id, payload,
                checksum(FORMAT_VERSION, sequence, kind, id, payload));
    }

    private byte[] encode(Frame frame) throws IOException {
        byte[] json = mapper.writeValueAsBytes(frame);
        if (json.length > MAX_FRAME_BYTES) throw new IOException("Recovery journal frame exceeds maximum size");
        byte[] line = new byte[json.length + 1];
        System.arraycopy(json, 0, line, 0, json.length);
        line[line.length - 1] = '\n';
        return line;
    }

    private void ensureWritable() throws IOException {
        if (writeFailure != null) throw new IOException("Recovery journal is write-failed; preserve and inspect it", writeFailure);
    }

    private static void validateRecord(String id, String payload) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (payload == null) throw new IllegalArgumentException("payload must not be null");
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("payload exceeds maximum size");
        }
    }

    private static String checksum(int version, long sequence, String kind, String id, String payload) {
        String canonical = version + "\n" + sequence + "\n" + kind + "\n" + id + "\n" + payload;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("Recovery journal contains invalid UTF-8; original bytes were preserved", malformed);
        }
    }

    private static IOException corrupt(int line, String reason) {
        return new IOException("Recovery journal line " + line + " is corrupt (" + reason + "); original was preserved");
    }

    private static IOException corrupt(int line, String reason, Throwable cause) {
        return new IOException("Recovery journal line " + line + " is corrupt (" + reason + "); original was preserved", cause);
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeFully(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) channel.write(bytes);
    }

    private static void forceDirectory(Path directory) throws IOException {
        if (directory == null) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Atomic replacement and the forced temporary file remain the available guarantees.
        }
    }
}
