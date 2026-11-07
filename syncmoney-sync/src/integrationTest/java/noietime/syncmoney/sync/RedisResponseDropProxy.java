package noietime.syncmoney.sync;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A RESP2 test proxy that can drop one EVALSHA response after Redis executes it. */
public final class RedisResponseDropProxy implements AutoCloseable {
    private final int upstreamPort;
    private final ServerSocket serverSocket;
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "syncmoney-redis-response-proxy");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean dropNextEvalSha = new AtomicBoolean();
    private final AtomicBoolean responseDropped = new AtomicBoolean();
    private final Thread acceptThread;

    private RedisResponseDropProxy(int upstreamPort, ServerSocket serverSocket) {
        this.upstreamPort = upstreamPort;
        this.serverSocket = serverSocket;
        this.acceptThread = new Thread(this::acceptConnections, "syncmoney-redis-proxy-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    public static RedisResponseDropProxy start(int upstreamPort) throws IOException {
        ServerSocket serverSocket = new ServerSocket(0, 32, InetAddress.getLoopbackAddress());
        return new RedisResponseDropProxy(upstreamPort, serverSocket);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public void dropNextEvalShaResponse() {
        responseDropped.set(false);
        dropNextEvalSha.set(true);
    }

    public boolean didDropResponse() {
        return responseDropped.get();
    }

    private void acceptConnections() {
        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                workers.execute(() -> proxy(client));
            } catch (IOException e) {
                if (!serverSocket.isClosed()) throw new IllegalStateException("Redis proxy accept failed", e);
            }
        }
    }

    private void proxy(Socket client) {
        try (client; Socket upstream = new Socket(InetAddress.getLoopbackAddress(), upstreamPort)) {
            client.setTcpNoDelay(true);
            upstream.setTcpNoDelay(true);
            InputStream clientInput = client.getInputStream();
            OutputStream clientOutput = client.getOutputStream();
            InputStream upstreamInput = upstream.getInputStream();
            OutputStream upstreamOutput = upstream.getOutputStream();

            while (!client.isClosed()) {
                byte[] request = readFrame(clientInput);
                if (request == null) return;
                upstreamOutput.write(request);
                upstreamOutput.flush();

                byte[] response = readFrame(upstreamInput);
                if (response == null) return;
                if (isEvalSha(request) && dropNextEvalSha.compareAndSet(true, false)) {
                    responseDropped.set(true);
                    return;
                }
                clientOutput.write(response);
                clientOutput.flush();
            }
        } catch (IOException ignored) {
            // Closing a client after a deliberately dropped response is the test behavior.
        }
    }

    private static boolean isEvalSha(byte[] request) {
        String wire = new String(request, StandardCharsets.UTF_8);
        int arrayHeaderEnd = wire.indexOf("\r\n");
        int commandHeaderEnd = wire.indexOf("\r\n", arrayHeaderEnd + 2);
        if (arrayHeaderEnd < 0 || commandHeaderEnd < 0) return false;
        String commandHeader = wire.substring(arrayHeaderEnd + 2, commandHeaderEnd);
        if (!commandHeader.startsWith("$")) return false;
        int commandLength;
        try {
            commandLength = Integer.parseInt(commandHeader.substring(1));
        } catch (NumberFormatException ignored) {
            return false;
        }
        int commandStart = commandHeaderEnd + 2;
        if (commandStart + commandLength > request.length) return false;
        String command = wire.substring(commandStart, commandStart + commandLength);
        return "EVALSHA".equalsIgnoreCase(command);
    }

    private static byte[] readFrame(InputStream input) throws IOException {
        int marker = input.read();
        if (marker < 0) return null;

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(marker);
        switch (marker) {
            case '+', '-', ':', ',', '#', '_', '(' -> readLine(input, frame);
            case '$', '!', '=' -> {
                int length = Integer.parseInt(readLine(input, frame));
                if (length >= 0) readBytes(input, frame, length + 2);
            }
            case '*', '~', '>' -> {
                int count = Integer.parseInt(readLine(input, frame));
                for (int i = 0; i < count; i++) readFrameInto(input, frame);
            }
            case '%', '|' -> {
                int count = Integer.parseInt(readLine(input, frame));
                for (int i = 0; i < count * 2; i++) readFrameInto(input, frame);
            }
            default -> throw new IOException("Unsupported RESP marker: " + marker);
        }
        return frame.toByteArray();
    }

    private static void readFrameInto(InputStream input, ByteArrayOutputStream output) throws IOException {
        byte[] child = readFrame(input);
        if (child == null) throw new IOException("Redis closed inside a RESP frame");
        output.write(child);
    }

    private static String readLine(InputStream input, ByteArrayOutputStream output) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current < 0) throw new IOException("Redis closed inside a RESP line");
            output.write(current);
            if (previous == '\r' && current == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.US_ASCII);
            }
            line.write(current);
            previous = current;
        }
    }

    private static void readBytes(InputStream input, ByteArrayOutputStream output, int count) throws IOException {
        byte[] buffer = new byte[Math.min(count, 8192)];
        int remaining = count;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("Redis closed inside a RESP bulk value");
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    @Override
    public void close() throws Exception {
        serverSocket.close();
        acceptThread.join(TimeUnit.SECONDS.toMillis(2));
        workers.shutdownNow();
        workers.awaitTermination(2, TimeUnit.SECONDS);
    }
}
