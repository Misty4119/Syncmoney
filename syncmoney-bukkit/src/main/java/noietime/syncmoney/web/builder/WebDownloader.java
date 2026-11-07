package noietime.syncmoney.web.builder;

import noietime.syncmoney.Syncmoney;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.zip.GZIPInputStream;

/**
 * Downloads web frontend from GitHub repository.
 * Supports tar.gz format for GitHub Release assets.
 */
public class WebDownloader {

    private final Syncmoney plugin;
    private final String githubRepo;

    public WebDownloader(Syncmoney plugin, String githubRepo) {
        this.plugin = plugin;
        this.githubRepo = githubRepo;
    }

    /**
     * Legacy constructor for backwards compatibility.
     * The targetDir parameter is ignored; callers should use
     * {@link #downloadLatest(Path, Path)} or {@link #downloadVersion(String, Path, Path)}.
     */
    public WebDownloader(Syncmoney plugin, String githubRepo, Path ignoredTargetDir) {
        this(plugin, githubRepo);
    }

    /**
     * Download latest version from GitHub Releases.
     *
     * @param tarGzPath destination path for the downloaded archive
     * @param extractDir directory to extract into (strip first component)
     */
    public boolean downloadLatest(Path tarGzPath, Path extractDir) throws IOException, InterruptedException {
        String downloadUrl = "https://github.com/" + githubRepo + "/releases/latest/download/syncmoney-web.tar.gz";
        return download(downloadUrl, tarGzPath, extractDir);
    }

    /**
     * Legacy no-arg downloadLatest for callers that pre-date the path refactor.
     * Downloads into a {@code syncmoney-web/} sibling of the plugin data folder.
     */
    public boolean downloadLatest() throws IOException, InterruptedException {
        Path webDir = plugin.getDataFolder().toPath().resolve("syncmoney-web");
        return downloadLatest(webDir.resolve("syncmoney-web.tar.gz"), webDir);
    }

    /**
     * Download a specific version from GitHub Releases.
     * Both {@code vX.Y.Z} and {@code X.Y.Z} formats are accepted.
     *
     * @param version    release tag (with or without "v" prefix)
     * @param tarGzPath  destination path for the downloaded archive
     * @param extractDir directory to extract into (strip first component)
     */
    public boolean downloadVersion(String version, Path tarGzPath, Path extractDir) throws IOException, InterruptedException {
        String requestedVersion = version == null ? "" : version.trim();
        if (requestedVersion.isEmpty()) {
            throw new IllegalArgumentException("Web release version must not be blank");
        }

        List<String> candidateTags = new ArrayList<>();
        candidateTags.add(requestedVersion);
        if (requestedVersion.startsWith("v") && requestedVersion.length() > 1) {
            candidateTags.add(requestedVersion.substring(1));
        } else if (!requestedVersion.startsWith("v")) {
            candidateTags.add("v" + requestedVersion);
        }

        for (int index = 0; index < candidateTags.size(); index++) {
            String tag = candidateTags.get(index);
            String downloadUrl = "https://github.com/" + githubRepo + "/releases/download/" + tag
                    + "/syncmoney-web.tar.gz";
            if (download(downloadUrl, tarGzPath, extractDir)) {
                if (index > 0) {
                    plugin.getLogger().fine("Web release tag fallback succeeded with: " + tag);
                }
                return true;
            }
        }

        return false;
    }

    /**
     * Legacy single-argument downloadVersion — kept for callers that don't pass paths.
     */
    public boolean downloadVersion(String version) throws IOException, InterruptedException {
        Path webDir = plugin.getDataFolder().toPath().resolve("syncmoney-web");
        return downloadVersion(version, webDir.resolve("syncmoney-web.tar.gz"), webDir);
    }

    private boolean download(String url, Path tarGzPath, Path extractDir) throws IOException, InterruptedException {
        prepareExtractionRoot(extractDir);
        Path archivePath = prepareDownloadArchivePath(tarGzPath);
        plugin.getLogger().fine("Downloading web frontend from: " + url);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/gzip")
                .timeout(Duration.ofSeconds(120))
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            try (InputStream ignored = response.body()) {
                // Release the response body without retaining an unsuccessful download.
            }
            plugin.getLogger().warning("Download failed: HTTP " + response.statusCode() + " for " + url);
            return false;
        }

        Path temporaryArchive = Files.createTempFile(archivePath.getParent(), ".syncmoney-web-", ".download");
        try {
            try (InputStream is = response.body();
                    OutputStream os = Files.newOutputStream(temporaryArchive, StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                is.transferTo(os);
            }
            try {
                Files.move(temporaryArchive, archivePath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporaryArchive, archivePath, StandardCopyOption.REPLACE_EXISTING);
            }

            try {
                extractTarGz(archivePath, extractDir);
            } finally {
                Files.deleteIfExists(archivePath);
            }
        } finally {
            Files.deleteIfExists(temporaryArchive);
        }

        plugin.getLogger().fine("Web frontend downloaded successfully");
        return true;
    }

    /**
     * Extract a .tar.gz archive to the target directory.
     * Strips the first directory component (e.g., "syncmoney-web/") from entry
     * paths.
     */
    static void extractTarGz(Path tarGzPath, Path targetDir) throws IOException {
        Path targetRoot = prepareExtractionRoot(targetDir);
        try (InputStream fis = Files.newInputStream(tarGzPath);
                GZIPInputStream gzis = new GZIPInputStream(fis);
                TarInputStream tis = new TarInputStream(gzis)) {

            TarInputStream.TarEntry entry;
            while ((entry = tis.nextEntry()) != null) {
                if (entry.typeFlag() != 0 && entry.typeFlag() != '0' && entry.typeFlag() != '5') {
                    throw new IOException("Unsupported TAR entry type: " + (int) entry.typeFlag());
                }

                /*
                 * Strip the first path component (GitHub wraps contents in a top-level folder).
                 */
                Path entryPath = resolveArchiveEntryPath(targetRoot, entry.name());
                if (entryPath == null) {
                    continue;
                }
                if (entryPath.equals(targetRoot) && !entry.isDirectory()) {
                    throw new IOException("TAR file entry cannot replace the extraction root");
                }
                if (entry.isDirectory()) {
                    tis.finishEntry();
                    createDirectoriesWithoutLinks(targetRoot, entryPath);
                } else {
                    Path parent = entryPath.getParent();
                    createDirectoriesWithoutLinks(targetRoot, parent);
                    Path temporaryFile = Files.createTempFile(parent, ".syncmoney-web-", ".tmp");
                    try {
                        try (OutputStream os = Files.newOutputStream(temporaryFile,
                                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS)) {
                            tis.copyEntryTo(os);
                        }
                        tis.finishEntry();
                        rejectLinkOrSpecialComponents(targetRoot, entryPath);
                        try {
                            Files.move(temporaryFile, entryPath, StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                            Files.move(temporaryFile, entryPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } finally {
                        Files.deleteIfExists(temporaryFile);
                    }
                }
            }
        }
    }

    private static Path prepareExtractionRoot(Path targetDir) throws IOException {
        Path requestedRoot = targetDir.toAbsolutePath().normalize();
        rejectLinkedOrSpecialDirectoryPath(requestedRoot, true);
        Files.createDirectories(requestedRoot);
        rejectLinkedOrSpecialDirectoryPath(requestedRoot, false);
        return requestedRoot;
    }

    private static void rejectLinkedOrSpecialDirectoryPath(Path directory, boolean allowMissing)
            throws IOException {
        Path current = directory.getRoot();
        if (current == null) {
            throw new IOException("Extraction root must be an absolute directory path");
        }
        BasicFileAttributes attributes = readAttributesIfPresent(current);
        if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                || !attributes.isDirectory()) {
            throw new IOException("Extraction root path must start at a regular filesystem directory");
        }

        for (Path component : directory) {
            current = current.resolve(component);
            attributes = readAttributesIfPresent(current);
            if (attributes == null) {
                if (!allowMissing) {
                    throw new IOException("Extraction root directory does not exist");
                }
                continue;
            }
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("Refusing an extraction root path containing a link or special entry");
            }
            if (!attributes.isDirectory()) {
                throw new IOException("Extraction root path contains a non-directory entry");
            }
        }
    }

    private static Path prepareDownloadArchivePath(Path archivePath) throws IOException {
        Path absoluteArchivePath = archivePath.toAbsolutePath().normalize();
        Path parent = absoluteArchivePath.getParent();
        if (parent == null || parent.getRoot() == null) {
            throw new IOException("Download archive must have an existing parent directory");
        }

        Path current = parent.getRoot();
        BasicFileAttributes attributes = readAttributesIfPresent(current);
        if (attributes == null || attributes.isSymbolicLink() || attributes.isOther() || !attributes.isDirectory()) {
            throw new IOException("Download archive parent is not a regular directory");
        }
        for (Path component : current.relativize(parent)) {
            current = current.resolve(component);
            attributes = readAttributesIfPresent(current);
            if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                    || !attributes.isDirectory()) {
                throw new IOException("Download archive parent contains a link or non-directory entry");
            }
        }

        attributes = readAttributesIfPresent(absoluteArchivePath);
        if (attributes != null && (attributes.isSymbolicLink() || attributes.isOther()
                || !attributes.isRegularFile())) {
            throw new IOException("Download archive target is not a regular file");
        }
        return absoluteArchivePath;
    }

    private static Path resolveArchiveEntryPath(Path targetRoot, String archiveName) throws IOException {
        String entryName = archiveName.replace('\\', '/');
        rejectUnsafeArchiveName(entryName);

        int slashIndex = entryName.indexOf('/');
        if (slashIndex > 0) {
            entryName = entryName.substring(slashIndex + 1);
        }
        if (entryName.isEmpty()) {
            return null;
        }
        rejectUnsafeArchiveName(entryName);

        Path relative;
        try {
            relative = targetRoot.getFileSystem().getPath(entryName);
        } catch (java.nio.file.InvalidPathException invalidPath) {
            throw new IOException("Invalid TAR entry path", invalidPath);
        }
        if (relative.isAbsolute() || relative.getRoot() != null) {
            throw new IOException("Refusing absolute TAR entry path");
        }

        Path entryPath = targetRoot.resolve(relative).normalize();
        if (!entryPath.startsWith(targetRoot)) {
            throw new IOException("Refusing TAR entry outside extraction directory");
        }
        return entryPath;
    }

    private static void rejectUnsafeArchiveName(String entryName) throws IOException {
        if (entryName.startsWith("/") || entryName.matches("^[A-Za-z]:.*")) {
            throw new IOException("Refusing absolute TAR entry path");
        }
        for (String component : entryName.split("/", -1)) {
            if (component.equals("..")) {
                throw new IOException("Refusing TAR parent-directory entry");
            }
            if (component.endsWith(".") || component.endsWith(" ")) {
                throw new IOException("Refusing TAR path component with Windows-normalized suffix");
            }
        }
    }

    private static void createDirectoriesWithoutLinks(Path targetRoot, Path directory) throws IOException {
        Path current = targetRoot;
        for (Path component : targetRoot.relativize(directory)) {
            current = current.resolve(component);
            BasicFileAttributes attributes = readAttributesIfPresent(current);
            if (attributes == null) {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException racedCreation) {
                    attributes = readAttributesIfPresent(current);
                }
                if (attributes == null) {
                    attributes = readAttributesIfPresent(current);
                }
            }
            if (attributes != null && (attributes.isSymbolicLink() || attributes.isOther())) {
                throw new IOException("Refusing to extract through a link or special filesystem entry");
            }
            if (attributes != null && !attributes.isDirectory()) {
                throw new IOException("TAR parent path is not a directory");
            }
        }
    }

    private static void rejectLinkOrSpecialComponents(Path targetRoot, Path entryPath) throws IOException {
        Path current = targetRoot;
        for (Path component : targetRoot.relativize(entryPath)) {
            current = current.resolve(component);
            BasicFileAttributes attributes = readAttributesIfPresent(current);
            if (attributes != null && (attributes.isSymbolicLink() || attributes.isOther())) {
                throw new IOException("Refusing to extract through a link or special filesystem entry");
            }
        }
    }

    private static BasicFileAttributes readAttributesIfPresent(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    /**
     * Minimal TAR input stream reader.
     * Supports reading POSIX tar archives — does NOT depend on any third-party
     * library.
     * Only the fields required for extraction (name, size, type flag) are parsed.
     */
    private static class TarInputStream extends FilterInputStream {

        private static final int BLOCK_SIZE = 512;
        private long remaining = 0;
        private long padding = 0;

        TarInputStream(InputStream in) {
            super(in);
        }

        /**
         * Represents a single entry in a TAR archive.
         */
        record TarEntry(String name, long size, boolean isDirectory, char typeFlag) {
        }

        /**
         * Read the next tar entry header.
         *
         * @return The next TarEntry or null if the end of the archive is reached.
         */
        TarEntry nextEntry() throws IOException {
            finishEntry();

            byte[] header = in.readNBytes(BLOCK_SIZE);
            if (header.length == 0) {
                throw new EOFException("TAR archive ended without an end-of-archive block");
            }
            if (header.length < BLOCK_SIZE) {
                throw new EOFException("Unexpected end of TAR header");
            }

            /* An all-zero block signals end of archive. */
            boolean allZero = true;
            for (byte b : header) {
                if (b != 0) {
                    allZero = false;
                    break;
                }
            }
            if (allZero) {
                return null;
            }

            String name = parseString(header, 0, 100);
            char typeFlag = (char) header[156];
            long size = parseOctal(header, 124, 12);

            /* Handle GNU/POSIX long name prefix (bytes 345-500). */
            String prefix = parseString(header, 345, 155);
            if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }

            if (size < 0) {
                throw new IOException("Invalid negative TAR entry size");
            }
            boolean isDirectory = (typeFlag == '5') || name.endsWith("/");
            remaining = size;
            padding = (BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE;

            return new TarEntry(name, size, isDirectory, typeFlag);
        }

        /**
         * Consume the current entry's payload and padding, failing if either is truncated.
         */
        void finishEntry() throws IOException {
            /*
             * Skip remaining bytes from the current entry, plus padding to the next
             * 512-byte boundary.
             */
            long skip = remaining + padding;
            while (skip > 0) {
                long skipped = in.skip(skip);
                if (skipped > 0) {
                    skip -= skipped;
                    continue;
                }
                if (in.read() < 0) {
                    throw new EOFException("Unexpected end of TAR entry data");
                }
                skip--;
            }
            remaining = 0;
            padding = 0;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0)
                return -1;
            int b = in.read();
            if (b >= 0)
                remaining--;
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (remaining <= 0)
                return -1;
            int toRead = (int) Math.min(len, remaining);
            int n = in.read(buf, off, toRead);
            if (n > 0)
                remaining -= n;
            return n;
        }

        /**
         * Copy remaining entry data to an output stream.
         */
        void copyEntryTo(OutputStream out) throws IOException {
            byte[] buf = new byte[8192];
            int n;
            while ((n = read(buf, 0, buf.length)) > 0) {
                out.write(buf, 0, n);
            }
        }

        private static String parseString(byte[] buf, int offset, int length) {
            int end = offset;
            int max = Math.min(offset + length, buf.length);
            while (end < max && buf[end] != 0)
                end++;
            return new String(buf, offset, end - offset).trim();
        }

        private static long parseOctal(byte[] buf, int offset, int length) {
            int end = offset;
            int max = Math.min(offset + length, buf.length);
            while (end < max && buf[end] != 0 && buf[end] != ' ')
                end++;
            String s = new String(buf, offset, end - offset).trim();
            if (s.isEmpty())
                return 0;
            return Long.parseLong(s, 8);
        }
    }
}
