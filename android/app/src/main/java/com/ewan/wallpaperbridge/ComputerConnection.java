package com.ewan.wallpaperbridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** A real receiver check with a deadline independent of socket activity. */
final class ComputerConnection implements AutoCloseable {
    // Leave 500 ms for delivering the finished result to the UI.
    static final long TIMEOUT_MS = 9500;
    static final long RETRY_MS = 10000;
    enum Phase { IDLE, CHECKING, CONNECTED, FAILED }

    static final class Snapshot {
        final Phase phase;
        final long startedAt;
        final long finishedAt;
        final long deadlineNanos;
        final String error;
        final boolean attemptRunning;
        final long attemptStartedAt;

        Snapshot(Phase phase, long startedAt, long finishedAt, long deadlineNanos,
                 String error, boolean attemptRunning) {
            this(phase, startedAt, finishedAt, deadlineNanos, error, attemptRunning,
                    attemptRunning ? startedAt : 0);
        }

        Snapshot(Phase phase, long startedAt, long finishedAt, long deadlineNanos,
                 String error, boolean attemptRunning, long attemptStartedAt) {
            this.phase = phase;
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.deadlineNanos = deadlineNanos;
            this.error = error;
            this.attemptRunning = attemptRunning;
            this.attemptStartedAt = attemptStartedAt;
        }

        long remainingMs() {
            if (deadlineNanos == 0) return 0;
            return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
        }
    }

    interface Probe { void check(Attempt attempt) throws Exception; }
    interface Listener { void changed(Snapshot snapshot); }

    static final class Attempt {
        final long startedAt = System.currentTimeMillis();
        final long deadlineNanos;
        volatile boolean cancelled;
        volatile Socket socket;
        ScheduledFuture<?> watchdog;

        Attempt(long timeoutMs) {
            deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        }

        int timeout(int maximumMs) throws IOException {
            long left = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (cancelled || left <= 0) throw new IOException("连接检查已结束");
            return (int) Math.max(1, Math.min(maximumMs, left));
        }

        void cancel() {
            cancelled = true;
            Socket current = socket;
            if (current != null) {
                try { current.close(); } catch (IOException ignored) { }
            }
        }
    }

    private final Probe probe;
    private final Listener listener;
    private final long timeoutMs;
    private final ScheduledExecutorService timers;
    private final ExecutorService worker;
    private Snapshot result = new Snapshot(Phase.IDLE, 0, 0, 0, "", false);
    private Attempt active;
    private boolean closed;

    ComputerConnection(Probe probe, Listener listener) {
        this(probe, listener, TIMEOUT_MS, RETRY_MS);
    }

    // The configurable intervals let the host checks exercise real stalled sockets quickly.
    ComputerConnection(Probe probe, Listener listener, long timeoutMs, long retryMs) {
        this.probe = probe;
        this.listener = listener;
        this.timeoutMs = timeoutMs;
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, "catdiao-connection-check");
            thread.setDaemon(true);
            return thread;
        };
        timers = Executors.newScheduledThreadPool(2, threads);
        worker = Executors.newSingleThreadExecutor(threads);
        timers.scheduleWithFixedDelay(() -> request(false), retryMs, retryMs, TimeUnit.MILLISECONDS);
    }

    synchronized Snapshot snapshot() {
        if (active != null && System.nanoTime() >= active.deadlineNanos)
            finish(active, false, "本次连接检查超时，未收到电脑确认。");
        return new Snapshot(result.phase, result.startedAt, result.finishedAt,
                active == null ? 0 : active.deadlineNanos, result.error, active != null,
                active == null ? 0 : active.startedAt);
    }

    synchronized void request(boolean showProgress) {
        if (closed) return;
        if (active != null) {
            if (showProgress && result.phase != Phase.CHECKING) {
                result = new Snapshot(Phase.CHECKING, active.startedAt, 0,
                        active.deadlineNanos, "", true);
                listener.changed(snapshot());
            }
            return;
        }
        Attempt attempt = new Attempt(timeoutMs);
        active = attempt;
        if (showProgress) {
            result = new Snapshot(Phase.CHECKING, attempt.startedAt, 0,
                    attempt.deadlineNanos, "", true);
            listener.changed(snapshot());
        }
        attempt.watchdog = timers.schedule(() -> {
            finish(attempt, false, "本次连接检查超时，未收到电脑确认。");
        }, timeoutMs, TimeUnit.MILLISECONDS);
        worker.execute(() -> {
            try {
                probe.check(attempt);
                finish(attempt, true, "");
            } catch (Exception error) {
                String detail = error instanceof CheckFailure ? error.getMessage()
                        : "电脑接收程序没有响应。";
                finish(attempt, false, detail);
            }
        });
    }

    private synchronized void finish(Attempt attempt, boolean connected, String error) {
        if (closed || active != attempt) return;
        if (System.nanoTime() >= attempt.deadlineNanos) {
            connected = false;
            error = "本次连接检查超时，未收到电脑确认。";
            cancelAsync(attempt);
        }
        active = null;
        if (attempt.watchdog != null) attempt.watchdog.cancel(false);
        result = new Snapshot(connected ? Phase.CONNECTED : Phase.FAILED,
                attempt.startedAt, System.currentTimeMillis(), 0, error, false);
        listener.changed(snapshot());
    }

    @Override public void close() {
        Attempt stopped;
        synchronized (this) {
            closed = true;
            stopped = active;
            active = null;
        }
        timers.shutdownNow();
        worker.shutdownNow();
        if (stopped != null) cancelAsync(stopped);
    }

    private static void cancelAsync(Attempt attempt) {
        attempt.cancelled = true;
        Thread cancel = new Thread(attempt::cancel, "catdiao-connection-cancel");
        cancel.setDaemon(true);
        cancel.start();
    }

    static final class CheckFailure extends IOException {
        CheckFailure(String message) { super(message); }
    }

    static final class Reply {
        final int code;
        final Map<String, String> headers;
        final byte[] body;
        Reply(int code, Map<String, String> headers, byte[] body) {
            this.code = code;
            this.headers = headers;
            this.body = body;
        }
    }

    static void probe(String address, int port, String token, Attempt attempt) throws Exception {
        if (address.isEmpty() || token.isEmpty()) {
            throw new CheckFailure("还没有可用的电脑连接信息，请查看「小窝连接」。");
        }
        String challenge = UUID.randomUUID().toString().replace("-", "");
        Reply reply = exchange(address, port, "GET", "/connection-check?challenge=" + challenge,
                "X-CatDiao-Auth", proof(token, "request:" + challenge), null, attempt);
        if (reply.code == 404) throw new CheckFailure("电脑接收程序需要更新，才能确认本次连接。");
        if (reply.code == 403) throw new CheckFailure("电脑没有确认当前配对，请查看「小窝连接」。");
        if (reply.code != 200) throw new CheckFailure("电脑返回了 HTTP " + reply.code + "，本次没有连上。");
        String responseProof = reply.headers.get("x-catdiao-proof");
        if (responseProof == null || !MessageDigest.isEqual(
                proof(token, "response:" + challenge).getBytes(StandardCharsets.US_ASCII),
                responseProof.getBytes(StandardCharsets.US_ASCII))) {
            throw new CheckFailure("电脑没有返回有效的配对确认，本次没有连上。");
        }
        attempt.timeout(1);
    }

    static Reply exchange(String address, int port, String method, String path,
                          String authName, String authValue, byte[] payload, Attempt attempt) throws Exception {
        if (!address.matches("[0-9.]+")) throw new CheckFailure("电脑连接地址不可用。");
        InetAddress peer = InetAddress.getByName(address);
        if (!peer.isSiteLocalAddress() && !peer.isLoopbackAddress()) {
            throw new CheckFailure("电脑连接地址不在局域网内。");
        }
        Socket socket = new Socket();
        attempt.socket = socket;
        try {
            socket.connect(new InetSocketAddress(peer, port), attempt.timeout(3000));
            String request = method + " " + path + " HTTP/1.1\r\n" +
                    "Host: " + address + ":" + port + "\r\n" +
                    authName + ": " + authValue + "\r\n" +
                    (payload == null ? "" : "Content-Type: application/json; charset=utf-8\r\n" +
                            "Content-Length: " + payload.length + "\r\n") +
                    "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            if (payload != null) socket.getOutputStream().write(payload);
            socket.getOutputStream().flush();
            String[] headers = readHeaders(socket, attempt).split("\r\n");
            String[] status = headers[0].split(" ", 3);
            if (status.length < 2 || !status[0].startsWith("HTTP/"))
                throw new CheckFailure("电脑没有返回有效的连接确认。");
            int code;
            try { code = Integer.parseInt(status[1]); }
            catch (NumberFormatException error) { throw new CheckFailure("电脑没有返回有效的连接确认。"); }
            Map<String, String> fields = new HashMap<>();
            for (int index = 1; index < headers.length; index++) {
                int colon = headers[index].indexOf(':');
                if (colon > 0) fields.put(headers[index].substring(0, colon).toLowerCase(Locale.ROOT),
                        headers[index].substring(colon + 1).trim());
            }
            byte[] body = new byte[0];
            if (payload != null && code == 200) {
                int expected;
                try { expected = Integer.parseInt(fields.get("content-length")); }
                catch (NumberFormatException error) { throw new CheckFailure("电脑没有返回完整的收藏确认。"); }
                if (expected < 0 || expected > 65536) throw new CheckFailure("电脑返回的收藏确认大小不正确。");
                body = new byte[expected];
                int received = 0;
                InputStream input = socket.getInputStream();
                while (received < expected) {
                    socket.setSoTimeout(attempt.timeout(6000));
                    int count = input.read(body, received, expected - received);
                    if (count < 0) throw new CheckFailure("电脑的收藏确认没有传完，请稍后重试。");
                    received += count;
                }
            }
            attempt.timeout(1);
            return new Reply(code, fields, body);
        } finally {
            socket.close();
            attempt.socket = null;
        }
    }

    private static String readHeaders(Socket socket, Attempt attempt) throws IOException {
        InputStream input = socket.getInputStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
        int ending = 0;
        while (bytes.size() < 8192) {
            // Recompute the total remaining time for every read, even when bytes keep arriving.
            socket.setSoTimeout(attempt.timeout(6000));
            int value = input.read();
            if (value < 0) throw new CheckFailure("电脑没有返回完整的连接确认。");
            bytes.write(value);
            ending = (ending << 8) | value;
            if (ending == 0x0d0a0d0a) return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
        }
        throw new CheckFailure("电脑返回的连接确认过长，本次没有连上。");
    }

    private static String proof(String token, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = mac.doFinal(message.getBytes(StandardCharsets.US_ASCII));
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return text.toString();
    }
}
