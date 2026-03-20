package noietime.syncmoney.web.builder;

import noietime.syncmoney.Syncmoney;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
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
        String normalizedVersion = version.startsWith("v") ? version : "v" + version;
        String downloadUrl = "https://github.com/" + githubRepo + "/releases/download/" + normalizedVersion + "/syncmoney-web.tar.gz";
        return download(downloadUrl, tarGzPath, extractDir);
    }

    /**
     * Legacy single-argument downloadVersion — kept for callers that don't pass paths.
     */
    public boolean downloadVersion(String version) throws IOException, InterruptedException {
        Path webDir = plugin.getDataFolder().toPath().resolve("syncmoney-web");
        return downloadVersion(version, webDir.resolve("syncmoney-web.tar.gz"), webDir);
    }

    private boolean download(String url, Path tarGzPath, Path extractDir) throws IOException, InterruptedException {
        plugin.getLogger().fine("Downloading web frontend from: " + url);

        if (!Files.exists(extractDir)) {
            Files.createDirectories(extractDir);
        }

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
            plugin.getLogger().severe("Download failed: HTTP " + response.statusCode());
            return false;
        }

        try (InputStream is = response.body();
                FileOutputStream fos = new FileOutputStream(tarGzPath.toFile())) {
            is.transferTo(fos);
        }

        try {
            extractTarGz(tarGzPath, extractDir);
        } finally {
            Files.deleteIfExists(tarGzPath);
        }

        plugin.getLogger().fine("Web frontend downloaded successfully");
        return true;
    }

    /**
     * Extract a .tar.gz archive to the target directory.
     * Strips the first directory component (e.g., "syncmoney-web/") from entry
     * paths.
     */
    private void extractTarGz(Path tarGzPath, Path targetDir) throws IOException {
        try (InputStream fis = Files.newInputStream(tarGzPath);
                GZIPInputStream gzis = new GZIPInputStream(fis);
                TarInputStream tis = new TarInputStream(gzis)) {

            TarInputStream.TarEntry entry;
            while ((entry = tis.nextEntry()) != null) {
                String entryName = entry.name();

                /*
                 * Strip the first path component (GitHub wraps contents in a top-level folder).
                 */
                int slashIndex = entryName.indexOf('/');
                if (slashIndex > 0) {
                    entryName = entryName.substring(slashIndex + 1);
                }

                if (entryName.isEmpty()) {
                    continue;
                }

                Path entryPath = targetDir.resolve(entryName);
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (OutputStream os = Files.newOutputStream(entryPath)) {
                        tis.copyEntryTo(os);
                    }
                }
            }
        }
        plugin.getLogger().fine("Web frontend extracted to: " + targetDir);
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

        TarInputStream(InputStream in) {
            super(in);
        }

        /**
         * Represents a single entry in a TAR archive.
         */
        record TarEntry(String name, long size, boolean isDirectory) {
        }

        /**
         * Read the next tar entry header.
         *
         * @return The next TarEntry or null if the end of the archive is reached.
         */
        TarEntry nextEntry() throws IOException {
            /*
             * Skip remaining bytes from the previous entry, plus padding to the next
             * 512-byte boundary.
             */
            if (remaining > 0) {
                long skip = remaining;
                long pad = (BLOCK_SIZE - (remaining % BLOCK_SIZE)) % BLOCK_SIZE;
                skip += pad;
                long skipped = 0;
                while (skipped < skip) {
                    long n = in.skip(skip - skipped);
                    if (n <= 0)
                        break;
                    skipped += n;
                }
            }

            byte[] header = in.readNBytes(BLOCK_SIZE);
            if (header.length < BLOCK_SIZE) {
                return null;
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

            boolean isDirectory = (typeFlag == '5') || name.endsWith("/");
            remaining = isDirectory ? 0 : size;

            return new TarEntry(name, size, isDirectory);
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
