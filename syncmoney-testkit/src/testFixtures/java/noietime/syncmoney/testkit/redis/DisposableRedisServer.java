package noietime.syncmoney.testkit.redis;

import java.io.InputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/** A loopback-only Redis process with a unique temporary data directory and automatic cleanup. */
public final class DisposableRedisServer implements AutoCloseable {
    private static final Duration START_TIMEOUT = Duration.ofSeconds(10);
    private static final String HOST = "127.0.0.1";

    private final int port;
    private final String password;
    private final Path dataDirectory;
    private final Path processIdFile;
    private final Process process;

    private DisposableRedisServer(int port, String password, Path dataDirectory, Path processIdFile, Process process) {
        this.port = port;
        this.password = password;
        this.dataDirectory = dataDirectory;
        this.processIdFile = processIdFile;
        this.process = process;
    }

    public static DisposableRedisServer start() throws Exception {
        String executable = System.getenv().getOrDefault("SYNCMONEY_TEST_REDIS_SERVER", "redis-server");
        return start(executable);
    }

    static DisposableRedisServer start(String executable) throws Exception {
        int port = availablePort();
        Path dataDirectory = Files.createTempDirectory("syncmoney-redis-test-");
        Path processIdFile = dataDirectory.resolve("redis.pid");
        String password = UUID.randomUUID().toString().replace("-", "");
        List<String> command = List.of(executable, "--bind", HOST, "--port", Integer.toString(port),
                "--save", "", "--appendonly", "no", "--requirepass", password,
                "--dir", dataDirectory.toString(), "--pidfile", processIdFile.toString());
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException startupFailure) {
            try {
                deleteOwnedDirectory(dataDirectory);
            } catch (IOException cleanupFailure) {
                startupFailure.addSuppressed(cleanupFailure);
            }
            throw startupFailure;
        }
        DisposableRedisServer server = new DisposableRedisServer(port, password, dataDirectory, processIdFile, process);
        try {
            server.awaitReady();
            return server;
        } catch (Exception failure) {
            try {
                server.close();
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public String host() {
        return HOST;
    }

    public int port() {
        return port;
    }

    public String password() {
        return password;
    }

    private void awaitReady() throws Exception {
        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        long redisProcessId = -1;
        long configuredProcessId = -1;
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("Isolated Redis launcher exited during startup; PID file: "
                        + readProcessIdFile());
            }
            try {
                configuredProcessId = readProcessIdFile();
                redisProcessId = pingAndReadProcessId();
                if (redisProcessId >= 0 && redisProcessId == configuredProcessId) return;
            } catch (IOException ignored) {
                TimeUnit.MILLISECONDS.sleep(50);
            }
        }
        throw new IllegalStateException("Isolated Redis process ownership could not be verified: launcher PID "
                + process.pid() + ", configured Redis PID " + configuredProcessId
                + ", Redis INFO PID " + redisProcessId);
    }

    private long pingAndReadProcessId() throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, port), 500);
            socket.setSoTimeout(500);
            return authenticateAndReadProcessId(socket);
        }
    }

    private long authenticateAndReadProcessId(Socket socket) throws IOException {
        InputStream response = socket.getInputStream();
        OutputStream output = socket.getOutputStream();
        writeCommand(output, "AUTH", password);
        if (!"+OK".equals(readRespLine(response))) return -1;
        writeCommand(output, "INFO", "server");
        String serverInfo = readRespBulkString(response);
        return serverInfo.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("process_id:"))
                .map(line -> line.substring("process_id:".length()))
                .map(this::parseProcessId)
                .findFirst()
                .orElse(-1L);
    }

    private long parseProcessId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalidProcessId) {
            return -1;
        }
    }

    private long readProcessIdFile() throws IOException {
        if (!Files.isRegularFile(processIdFile)) return -1;
        try {
            return parseProcessId(Files.readString(processIdFile, StandardCharsets.US_ASCII).strip());
        } catch (NumberFormatException invalidPid) {
            return -1;
        }
    }

    private static void writeCommand(OutputStream output, String... arguments) throws IOException {
        output.write(("*" + arguments.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String argument : arguments) {
            byte[] bytes = argument.getBytes(StandardCharsets.UTF_8);
            output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        output.flush();
    }

    private static String readRespLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (line.size() < 8_192) {
            int next = input.read();
            if (next < 0) throw new IOException("Redis closed the connection before a RESP line completed");
            if (previous == '\r' && next == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
            }
            line.write(next);
            previous = next;
        }
        throw new IOException("Redis RESP line exceeded the fixture limit");
    }

    private static String readRespBulkString(InputStream input) throws IOException {
        String header = readRespLine(input);
        if (!header.startsWith("$")) throw new IOException("Redis INFO response was not a RESP bulk string");
        int length;
        try {
            length = Integer.parseInt(header.substring(1));
        } catch (NumberFormatException invalidLength) {
            throw new IOException("Redis INFO response had an invalid bulk-string length", invalidLength);
        }
        if (length < 0 || length > 1_048_576) throw new IOException("Redis INFO response length was out of range");
        byte[] payload = input.readNBytes(length);
        if (payload.length != length || input.read() != '\r' || input.read() != '\n') {
            throw new IOException("Redis INFO response ended before the bulk string completed");
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    @Override
    public void close() throws Exception {
        if (process.isAlive()) {
            try {
                shutdownVerifiedRedis();
            } catch (IOException ignored) {
                // The process handle remains the safe fallback if Redis no longer accepts commands.
            }
            awaitRedisStopped();
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    throw new IOException("Unable to stop isolated Redis process");
                }
            }
        }
        deleteOwnedDirectory(dataDirectory);
    }

    private void shutdownVerifiedRedis() throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, port), 500);
            socket.setSoTimeout(500);
            long respondingProcessId = authenticateAndReadProcessId(socket);
            long ownedProcessId = readProcessIdFile();
            if (ownedProcessId < 0 || respondingProcessId != ownedProcessId) {
                throw new IOException("Redis endpoint does not match the owned PID file");
            }
            writeCommand(socket.getOutputStream(), "SHUTDOWN", "NOSAVE");
        }
    }

    private void awaitRedisStopped() throws InterruptedException, IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            try {
                if (pingAndReadProcessId() != readProcessIdFile()) return;
            } catch (IOException endpointUnavailable) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new IOException("Isolated Redis remained available after shutdown");
    }

    private static void deleteOwnedDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
