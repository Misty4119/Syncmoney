package noietime.syncmoney.sync;

import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class RedisProcessTestSupport implements AutoCloseable {
    private static final Duration START_TIMEOUT = Duration.ofSeconds(10);

    private final int port;
    private final Path dataDirectory;
    private final Path outputFile;
    private final Process process;

    private RedisProcessTestSupport(int port, Path dataDirectory, Path outputFile, Process process) {
        this.port = port;
        this.dataDirectory = dataDirectory;
        this.outputFile = outputFile;
        this.process = process;
    }

    public static RedisProcessTestSupport start() throws Exception {
        int port = availablePort();
        Path dataDirectory = Files.createTempDirectory("syncmoney-redis-it-");
        Path outputFile = dataDirectory.resolve("redis.log");
        String executable = System.getenv().getOrDefault("SYNCMONEY_TEST_REDIS_SERVER", "redis-server");
        List<String> command = List.of(executable, "--bind", "127.0.0.1", "--port", Integer.toString(port),
                "--save", "", "--appendonly", "no", "--dir", dataDirectory.toString());
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start();
        RedisProcessTestSupport server = new RedisProcessTestSupport(port, dataDirectory, outputFile, process);
        try {
            server.awaitReady();
            return server;
        } catch (Exception e) {
            server.close();
            throw e;
        }
    }

    public Jedis connect() {
        return new Jedis("127.0.0.1", port, 500, 1_000);
    }

    public int port() {
        return port;
    }

    private void awaitReady() throws Exception {
        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("Isolated Redis exited during startup: " + readOutput());
            }
            try (Jedis jedis = connect()) {
                if ("PONG".equals(jedis.ping())) return;
            } catch (RuntimeException ignored) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("Isolated Redis did not become ready: " + readOutput());
    }

    private String readOutput() throws IOException {
        return Files.exists(outputFile) ? Files.readString(outputFile, StandardCharsets.UTF_8) : "<no Redis output>";
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    @Override
    public void close() throws Exception {
        if (process.isAlive()) {
            try (Jedis jedis = connect()) {
                jedis.shutdown();
            } catch (RuntimeException ignored) {
                process.destroy();
            }
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        }
        if (Files.exists(dataDirectory)) {
            try (var paths = Files.walk(dataDirectory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
