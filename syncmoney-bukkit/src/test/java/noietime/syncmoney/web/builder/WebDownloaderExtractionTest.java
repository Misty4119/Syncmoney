package noietime.syncmoney.web.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebDownloaderExtractionTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsPrefixedTraversalWithoutChangingFilesOutsideTheWebRoot() throws Exception {
        Path targetDir = tempDir.resolve("plugins/Syncmoney/syncmoney-web");
        Files.createDirectories(targetDir);
        Path outsideFile = tempDir.resolve("outside.txt");
        Files.writeString(outsideFile, "retained sentinel", StandardCharsets.UTF_8);
        Path archive = writeTarGz("syncmoney-web/../../outside.txt", "overwritten");

        assertThrows(IOException.class, () -> WebDownloader.extractTarGz(archive, targetDir));
        assertEquals("retained sentinel", Files.readString(outsideFile, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsWindowsNormalizedDotAndSpaceTraversalWithoutChangingOutsideFiles() throws Exception {
        Path targetDir = tempDir.resolve("plugins/Syncmoney/syncmoney-web");
        Files.createDirectories(targetDir);
        Path outsideFile = targetDir.getParent().resolve("outside.txt");
        String sentinel = "retained sentinel";
        Files.writeString(outsideFile, sentinel, StandardCharsets.UTF_8);

        for (String unsafeComponent : new String[] {".. ", ".. .", "...", "..   "}) {
            Path archive = writeTarGz("syncmoney-web/" + unsafeComponent + "/outside.txt", "overwritten");

            assertThrows(IOException.class, () -> WebDownloader.extractTarGz(archive, targetDir), unsafeComponent);
            assertEquals(sentinel, Files.readString(outsideFile, StandardCharsets.UTF_8), unsafeComponent);
        }
    }

    @Test
    void extractsWrappedNestedWebFiles() throws Exception {
        Path targetDir = tempDir.resolve("web-root");
        Files.createDirectories(targetDir);
        Path archive = writeTarGz("syncmoney-web/dist/index.html", "<!doctype html>");

        WebDownloader.extractTarGz(archive, targetDir);

        assertEquals("<!doctype html>", Files.readString(targetDir.resolve("dist/index.html")));
    }

    @Test
    void extractsEveryFileWhenPreviousTarMemberNeedsPadding() throws Exception {
        Path targetDir = tempDir.resolve("web-root");
        Files.createDirectories(targetDir);
        Path archive = writeTarGzFiles(
                "syncmoney-web/first.txt", "first",
                "syncmoney-web/second.txt", "second");

        WebDownloader.extractTarGz(archive, targetDir);

        assertEquals("first", Files.readString(targetDir.resolve("first.txt")));
        assertEquals("second", Files.readString(targetDir.resolve("second.txt")));
    }

    @Test
    void rejectsNonEmptyPartialTarHeader() throws Exception {
        Path targetDir = tempDir.resolve("web-root");
        Files.createDirectories(targetDir);
        Path archive = writeTarGzWithPartialHeader();

        assertThrows(IOException.class, () -> WebDownloader.extractTarGz(archive, targetDir));
    }

    @Test
    void rejectsTruncatedMemberPayloadAndPadding() throws Exception {
        Path targetDir = tempDir.resolve("web-root");
        Files.createDirectories(targetDir);
        Path existingTarget = targetDir.resolve("file.txt");
        String existingContents = "previous release contents";
        Files.writeString(existingTarget, existingContents, StandardCharsets.UTF_8);
        String contents = "payload";
        Path completeArchive = writeTarGz("syncmoney-web/file.txt", contents);
        Path truncatedPayload = writeTruncatedTarGz(completeArchive,
                512 + contents.getBytes(StandardCharsets.UTF_8).length - 1,
                "truncated-payload.tar.gz");
        Path truncatedPadding = writeTruncatedTarGz(completeArchive,
                512 + contents.getBytes(StandardCharsets.UTF_8).length,
                "truncated-padding.tar.gz");

        assertThrows(IOException.class, () -> WebDownloader.extractTarGz(truncatedPayload, targetDir));
        assertEquals(existingContents, Files.readString(existingTarget, StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> WebDownloader.extractTarGz(truncatedPadding, targetDir));
        assertEquals(existingContents, Files.readString(existingTarget, StandardCharsets.UTF_8));

        Path emptyTargetDir = tempDir.resolve("empty-web-root");
        Files.createDirectories(emptyTargetDir);
        Path absentTarget = emptyTargetDir.resolve("file.txt");
        assertThrows(IOException.class, () -> WebDownloader.extractTarGz(truncatedPayload, emptyTargetDir));
        assertFalse(Files.exists(absentTarget));
        assertThrows(IOException.class, () -> WebDownloader.extractTarGz(truncatedPadding, emptyTargetDir));
        assertFalse(Files.exists(absentTarget));
    }

    @Test
    void rejectsAbsoluteBackslashAndUstarPrefixTraversal() throws Exception {
        Path targetDir = tempDir.resolve("target/web");
        Files.createDirectories(targetDir);
        Path absoluteMember = tempDir.resolve("absolute-escape.txt").toString().contains(":")
                ? tempDir.resolve("absolute-escape.txt") : Path.of("/absolute-escape.txt");
        String[] unsafeNames = {
            absoluteMember.toString(),
            "syncmoney-web/C:/drive-escape.txt",
            "syncmoney-web/\\\\server\\share\\unc-escape.txt",
            "syncmoney-web\\..\\..\\backslash-escape.txt"
        };
        for (String name : unsafeNames) {
            Path archive = writeTarGz(name, "unsafe");
            assertThrows(IOException.class, () -> WebDownloader.extractTarGz(archive, targetDir), name);
        }

        Path prefixedArchive = writeTarGz("syncmoney-web/../../", "prefix-escape.txt", "unsafe", '0');
        assertThrows(IOException.class, () -> WebDownloader.extractTarGz(prefixedArchive, targetDir));
    }

    @Test
    void rejectsTarLinkEntries() throws Exception {
        Path targetDir = tempDir.resolve("web-root");
        Files.createDirectories(targetDir);
        Path archive = writeTarGz("syncmoney-web/link", "target.txt", '2');

        assertThrows(IOException.class, () -> WebDownloader.extractTarGz(archive, targetDir));
        assertFalse(Files.exists(targetDir.resolve("link")));
    }

    @Test
    void refusesExistingSymlinkedParentsAndTargets() throws Exception {
        Path targetDir = tempDir.resolve("web-root");
        Path outsideDir = tempDir.resolve("outside");
        Files.createDirectories(targetDir);
        Files.createDirectories(outsideDir);
        Path sentinel = outsideDir.resolve("sentinel.txt");
        Files.writeString(sentinel, "retained sentinel", StandardCharsets.UTF_8);
        createSymbolicLinkOrSkip(targetDir.resolve("linked-dir"), outsideDir);
        createSymbolicLinkOrSkip(targetDir.resolve("linked-file.txt"), sentinel);

        Path parentArchive = writeTarGz("syncmoney-web/linked-dir/escape.txt", "overwritten");
        assertThrows(IOException.class, () -> WebDownloader.extractTarGz(parentArchive, targetDir));
        Path targetArchive = writeTarGz("syncmoney-web/linked-file.txt", "overwritten");
        assertThrows(IOException.class, () -> WebDownloader.extractTarGz(targetArchive, targetDir));
        assertEquals("retained sentinel", Files.readString(sentinel, StandardCharsets.UTF_8));
        assertFalse(Files.exists(outsideDir.resolve("escape.txt")));
    }

    @Test
    void rejectsSymlinkedExtractionRootWithoutWritingOutsideIt() throws Exception {
        Path targetParent = tempDir.resolve("plugins/Syncmoney");
        Path targetDir = targetParent.resolve("syncmoney-web");
        Path outsideDir = tempDir.resolve("outside-web-root");
        Files.createDirectories(targetParent);
        Files.createDirectories(outsideDir);
        Path sentinel = outsideDir.resolve("sentinel.txt");
        Files.writeString(sentinel, "retained sentinel", StandardCharsets.UTF_8);
        createSymbolicLinkOrSkip(targetDir, outsideDir);
        Path archive = writeTarGz("syncmoney-web/dist/index.html", "unsafe write");

        assertThrows(IOException.class, () -> WebDownloader.extractTarGz(archive, targetDir));
        assertEquals("retained sentinel", Files.readString(sentinel, StandardCharsets.UTF_8));
        assertFalse(Files.exists(outsideDir.resolve("dist/index.html")));
    }

    @Test
    void rejectsSymlinkedExtractionRootAncestorWithoutWritingOutsideIt() throws Exception {
        Path pluginRoot = tempDir.resolve("plugins/Syncmoney");
        Path outsideDir = tempDir.resolve("outside-plugin-root");
        Files.createDirectories(pluginRoot.getParent());
        Files.createDirectories(outsideDir);
        Path sentinel = outsideDir.resolve("sentinel.txt");
        Files.writeString(sentinel, "retained sentinel", StandardCharsets.UTF_8);
        createSymbolicLinkOrSkip(pluginRoot, outsideDir);
        Path targetDir = pluginRoot.resolve("syncmoney-web");
        Path archive = writeTarGz("syncmoney-web/dist/index.html", "unsafe write");

        assertThrows(IOException.class, () -> WebDownloader.extractTarGz(archive, targetDir));
        assertEquals("retained sentinel", Files.readString(sentinel, StandardCharsets.UTF_8));
        assertFalse(Files.exists(outsideDir.resolve("syncmoney-web/dist/index.html")));
    }

    @Test
    void rejectsSymlinkedExtractionRootBeforeStartingDownload() throws Exception {
        Path targetParent = tempDir.resolve("plugins/Syncmoney");
        Path targetDir = targetParent.resolve("syncmoney-web");
        Path outsideDir = tempDir.resolve("outside-web-root");
        Files.createDirectories(targetParent);
        Files.createDirectories(outsideDir);
        createSymbolicLinkOrSkip(targetDir, outsideDir);
        Path downloadedArchive = targetDir.resolve("syncmoney-web.tar.gz");
        WebDownloader downloader = new WebDownloader(null, "unused/repository");

        assertThrows(IOException.class, () -> downloader.downloadLatest(downloadedArchive, targetDir));
        assertFalse(Files.exists(outsideDir.resolve(downloadedArchive.getFileName())));
    }

    @Test
    void rejectsSymlinkedDownloadArchiveBeforeStartingNetworkRequest() throws Exception {
        Path targetDir = tempDir.resolve("web-root");
        Path outsideFile = tempDir.resolve("outside-archive.tar.gz");
        Files.createDirectories(targetDir);
        Files.writeString(outsideFile, "retained sentinel", StandardCharsets.UTF_8);
        Path downloadedArchive = targetDir.resolve("syncmoney-web.tar.gz");
        createSymbolicLinkOrSkip(downloadedArchive, outsideFile);
        WebDownloader downloader = new WebDownloader(null, "unused/repository");

        assertThrows(IOException.class, () -> downloader.downloadLatest(downloadedArchive, targetDir));
        assertEquals("retained sentinel", Files.readString(outsideFile, StandardCharsets.UTF_8));
    }

    @Test
    void replacesAnExistingHardLinkWithoutWritingThroughIt() throws Exception {
        Path targetDir = tempDir.resolve("web-root");
        Files.createDirectories(targetDir);
        Path outsideFile = tempDir.resolve("outside-hardlink.txt");
        Files.writeString(outsideFile, "retained sentinel", StandardCharsets.UTF_8);
        createHardLinkOrSkip(targetDir.resolve("linked-file.txt"), outsideFile);
        Path archive = writeTarGz("syncmoney-web/linked-file.txt", "replacement");

        WebDownloader.extractTarGz(archive, targetDir);

        assertEquals("retained sentinel", Files.readString(outsideFile, StandardCharsets.UTF_8));
        assertEquals("replacement", Files.readString(targetDir.resolve("linked-file.txt")));
    }

    private Path writeTarGz(String entryName, String contents) throws IOException {
        return writeTarGz("", entryName, contents, '0');
    }

    private Path writeTarGz(String entryName, String contents, char typeFlag) throws IOException {
        return writeTarGz("", entryName, contents, typeFlag);
    }

    private Path writeTarGz(String prefix, String entryName, String contents, char typeFlag) throws IOException {
        Path archive = tempDir.resolve("fixture.tar.gz");
        byte[] name = entryName.getBytes(StandardCharsets.UTF_8);
        byte[] payload = contents.getBytes(StandardCharsets.UTF_8);
        byte[] header = new byte[512];
        System.arraycopy(name, 0, header, 0, name.length);
        writeOctal(header, 100, 8, 0644);
        writeOctal(header, 108, 8, 0);
        writeOctal(header, 116, 8, 0);
        writeOctal(header, 124, 12, payload.length);
        writeOctal(header, 136, 12, 0);
        Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = (byte) typeFlag;
        byte[] magic = "ustar\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, header, 257, magic.length);
        header[263] = '0';
        header[264] = '0';
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(prefixBytes, 0, header, 345, prefixBytes.length);
        long checksum = 0;
        for (byte value : header) checksum += Byte.toUnsignedInt(value);
        byte[] checksumField = String.format(Locale.ROOT, "%06o\0 ", checksum)
                .getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(checksumField, 0, header, 148, checksumField.length);

        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(archive))) {
            gzip.write(header);
            gzip.write(payload);
            int padding = (512 - payload.length % 512) % 512;
            gzip.write(new byte[padding]);
            gzip.write(new byte[1024]);
        }
        return archive;
    }

    private Path writeTarGzFiles(String... entryNamesAndContents) throws IOException {
        if (entryNamesAndContents.length == 0 || entryNamesAndContents.length % 2 != 0) {
            throw new IllegalArgumentException("TAR fixtures require name/content pairs");
        }
        Path archive = tempDir.resolve("multi-entry-fixture.tar.gz");
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(archive))) {
            for (int i = 0; i < entryNamesAndContents.length; i += 2) {
                byte[] name = entryNamesAndContents[i].getBytes(StandardCharsets.UTF_8);
                byte[] payload = entryNamesAndContents[i + 1].getBytes(StandardCharsets.UTF_8);
                byte[] header = new byte[512];
                System.arraycopy(name, 0, header, 0, name.length);
                writeOctal(header, 100, 8, 0644);
                writeOctal(header, 108, 8, 0);
                writeOctal(header, 116, 8, 0);
                writeOctal(header, 124, 12, payload.length);
                writeOctal(header, 136, 12, 0);
                Arrays.fill(header, 148, 156, (byte) ' ');
                header[156] = '0';
                byte[] magic = "ustar\0".getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(magic, 0, header, 257, magic.length);
                header[263] = '0';
                header[264] = '0';
                long checksum = 0;
                for (byte value : header) checksum += Byte.toUnsignedInt(value);
                byte[] checksumField = String.format(Locale.ROOT, "%06o\0 ", checksum)
                        .getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(checksumField, 0, header, 148, checksumField.length);

                gzip.write(header);
                gzip.write(payload);
                int padding = (512 - payload.length % 512) % 512;
                gzip.write(new byte[padding]);
            }
            gzip.write(new byte[1024]);
        }
        return archive;
    }

    private Path writeTarGzWithPartialHeader() throws IOException {
        Path archive = tempDir.resolve("partial-header-fixture.tar.gz");
        byte[] headerFragment = new byte[100];
        Arrays.fill(headerFragment, (byte) 'X');
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(archive))) {
            gzip.write(headerFragment);
        }
        return archive;
    }

    private Path writeTruncatedTarGz(Path completeArchive, int length, String fixtureName) throws IOException {
        byte[] tarContents;
        try (GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(completeArchive))) {
            tarContents = gzip.readAllBytes();
        }
        Path archive = tempDir.resolve(fixtureName);
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(archive))) {
            gzip.write(tarContents, 0, length);
        }
        return archive;
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable for this test process");
        } catch (IOException failure) {
            if (!isLinkCapabilityFailure(failure)) throw failure;
            Assumptions.assumeTrue(false, "Symbolic links are unavailable for this test process");
        }
    }

    private static void createHardLinkOrSkip(Path link, Path existingFile) throws IOException {
        try {
            Files.createLink(link, existingFile);
        } catch (UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "Hard links are unavailable for this test process");
        } catch (IOException failure) {
            if (!isLinkCapabilityFailure(failure)) throw failure;
            Assumptions.assumeTrue(false, "Hard links are unavailable for this test process");
        }
    }

    private static boolean isLinkCapabilityFailure(IOException failure) {
        if (failure instanceof AccessDeniedException) return true;
        if (!(failure instanceof FileSystemException fileSystemFailure)) return false;
        String reason = fileSystemFailure.getReason();
        String message = reason != null ? reason : fileSystemFailure.getMessage();
        if (message == null) return false;
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("privilege") || normalized.contains("operation not permitted")
                || normalized.contains("not supported") || normalized.contains("not permitted");
    }

    private static void writeOctal(byte[] header, int offset, int length, long value) {
        byte[] field = String.format(Locale.ROOT, "%0" + (length - 1) + "o", value)
                .getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(field, 0, header, offset, field.length);
    }

}
