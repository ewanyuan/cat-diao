package com.ewan.wallpaperbridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owns listeners and clients for one service instance; a failed bind can be retried. */
final class LocalBridgeServer implements AutoCloseable {
    interface Handler { void handle(Client client) throws Exception; }
    static final long HEADER_TIMEOUT_MS = 9500;
    private final int port;
    private final int discoveryPort;
    private final long headerTimeoutMs;
    private final Handler handler;
    private final Consumer<Exception> failure;
    private final ThreadPoolExecutor clients;
    private final ScheduledExecutorService timers;
    private final Set<Client> connected = ConcurrentHashMap.newKeySet();
    private ServerSocket server;
    private DatagramSocket discovery;
    private boolean closed;

    final class Client implements AutoCloseable {
        final Socket socket;
        final long headerDeadline;
        private volatile boolean headersComplete;
        private ScheduledFuture<?> watchdog;

        Client(Socket socket) throws IOException {
            this.socket = socket;
            headerDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(headerTimeoutMs);
            socket.setSoTimeout(20000);
        }

        void watch() {
            watchdog = timers.schedule(() -> {
                if (!headersComplete) close();
            }, headerTimeoutMs, TimeUnit.MILLISECONDS);
        }

        void watchBody(long timeoutMs) {
            if (watchdog != null) watchdog.cancel(false);
            watchdog = timers.schedule(this::close, timeoutMs, TimeUnit.MILLISECONDS);
        }

        void bodyReceived() {
            if (watchdog != null) watchdog.cancel(false);
        }

        String readHeaders() throws IOException {
            InputStream input = socket.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int ending = 0;
            while (bytes.size() < 8192) {
                long left = TimeUnit.NANOSECONDS.toMillis(headerDeadline - System.nanoTime());
                if (left <= 0) throw new SocketTimeoutException("请求头接收超时");
                socket.setSoTimeout((int) Math.max(1, left));
                int value = input.read();
                if (value < 0) throw new IOException("请求头未传完");
                bytes.write(value);
                ending = (ending << 8) | value;
                if (ending == 0x0d0a0d0a) {
                    headersComplete = true;
                    if (watchdog != null) watchdog.cancel(false);
                    socket.setSoTimeout(20000);
                    return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
                }
            }
            throw new IOException("请求头过长");
        }

        @Override public void close() {
            headersComplete = true;
            if (watchdog != null) watchdog.cancel(false);
            try { socket.close(); } catch (IOException ignored) { }
            connected.remove(this);
        }
    }

    LocalBridgeServer(int port, int discoveryPort, Handler handler, Consumer<Exception> failure) {
        this(port, discoveryPort, handler, failure, HEADER_TIMEOUT_MS);
    }

    LocalBridgeServer(int port, int discoveryPort, Handler handler, Consumer<Exception> failure,
                      long headerTimeoutMs) {
        this.port = port;
        this.discoveryPort = discoveryPort;
        this.handler = handler;
        this.failure = failure;
        this.headerTimeoutMs = headerTimeoutMs;
        ThreadFactory threads = work -> {
            Thread thread = new Thread(work, "catdiao-client");
            thread.setDaemon(true);
            return thread;
        };
        clients = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8), threads);
        timers = Executors.newSingleThreadScheduledExecutor(threads);
    }

    synchronized boolean isRunning() { return !closed && server != null && !server.isClosed(); }
    synchronized int localPort() { return server == null ? -1 : server.getLocalPort(); }

    synchronized void start() throws IOException {
        if (closed) return;
        if (server == null || server.isClosed()) {
            ServerSocket opened = new ServerSocket();
            try {
                opened.setReuseAddress(true);
                opened.bind(new InetSocketAddress(port));
            } catch (IOException error) {
                opened.close();
                throw error;
            }
            server = opened;
            Thread accept = new Thread(() -> accept(opened), "phone-bridge-server");
            accept.setDaemon(true);
            accept.start();
        }
        if (discoveryPort >= 0 && (discovery == null || discovery.isClosed())) {
            try {
                DatagramSocket opened = new DatagramSocket(discoveryPort);
                opened.setSoTimeout(2000);
                discovery = opened;
                Thread broadcast = new Thread(() -> discover(opened), "phone-bridge-discovery");
                broadcast.setDaemon(true);
                broadcast.start();
            } catch (IOException ignored) { }
        }
    }

    private synchronized boolean owns(ServerSocket owned) { return !closed && server == owned; }
    private synchronized boolean owns(DatagramSocket owned) { return !closed && discovery == owned; }

    private void accept(ServerSocket owned) {
        try {
            while (owns(owned)) {
                Socket socket = owned.accept();
                Client client;
                try { client = new Client(socket); }
                catch (IOException error) { socket.close(); continue; }
                connected.add(client);
                try {
                    client.watch();
                    clients.execute(() -> {
                        try (Client request = client) { handler.handle(request); }
                        catch (Exception ignored) { }
                    });
                } catch (RejectedExecutionException stoppedOrBusy) { client.close(); }
            }
        } catch (IOException error) {
            if (owns(owned)) failure.accept(error);
        } finally {
            try { owned.close(); } catch (IOException ignored) { }
            synchronized (this) { if (server == owned) server = null; }
        }
    }

    private void discover(DatagramSocket owned) {
        try {
            while (owns(owned)) {
                byte[] buffer = new byte[128];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try { owned.receive(packet); }
                catch (SocketTimeoutException ignored) { continue; }
                if (!isRunning() || (!packet.getAddress().isSiteLocalAddress()
                        && !packet.getAddress().isLoopbackAddress())) continue;
                String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.US_ASCII);
                if (!message.equals("PHONE_BRIDGE_DISCOVER_V1")) continue;
                byte[] answer = "PHONE_BRIDGE_V1:8767".getBytes(StandardCharsets.US_ASCII);
                owned.send(new DatagramPacket(answer, answer.length, packet.getAddress(), packet.getPort()));
            }
        } catch (IOException ignored) { }
        finally {
            owned.close();
            synchronized (this) { if (discovery == owned) discovery = null; }
        }
    }

    @Override public void close() {
        ServerSocket stoppedServer;
        DatagramSocket stoppedDiscovery;
        synchronized (this) {
            closed = true;
            stoppedServer = server;
            stoppedDiscovery = discovery;
            server = null;
            discovery = null;
        }
        try { if (stoppedServer != null) stoppedServer.close(); } catch (IOException ignored) { }
        if (stoppedDiscovery != null) stoppedDiscovery.close();
        clients.shutdownNow();
        timers.shutdownNow();
        closeClients();
    }

    void closeClients() { for (Client client : connected) client.close(); }
}
