package com.ewan.wallpaperbridge;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class ComputerConnectionTest {
    private static final String TOKEN = "test-pair-secret";

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String proof(String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TOKEN.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder result = new StringBuilder();
        for (byte value : mac.doFinal(text.getBytes(StandardCharsets.US_ASCII)))
            result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static final class Receiver implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final AtomicInteger calls = new AtomicInteger();
        final ExecutorService threads = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task); thread.setDaemon(true); return thread;
        });
        volatile boolean wrongProof;
        volatile int blockCall;
        final CountDownLatch blocked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        Receiver() throws Exception {
            server.setExecutor(threads);
            server.createContext("/connection-check", exchange -> {
                try {
                    int number = calls.incrementAndGet();
                    String challenge = exchange.getRequestURI().getQuery().substring("challenge=".length());
                    if (number == blockCall) { blocked.countDown(); release.await(3, TimeUnit.SECONDS); }
                    boolean authorized = proof("request:" + challenge).equals(
                            exchange.getRequestHeaders().getFirst("X-CatDiao-Auth"));
                    if (authorized) exchange.getResponseHeaders().set("X-CatDiao-Proof",
                            wrongProof ? "0" : proof("response:" + challenge));
                    byte[] body = "{}".getBytes(StandardCharsets.US_ASCII);
                    exchange.sendResponseHeaders(authorized ? 200 : 403, body.length);
                    exchange.getResponseBody().write(body);
                } catch (Exception error) { throw new java.io.IOException(error); }
                finally { exchange.close(); }
            });
            server.start();
        }
        int port() { return server.getAddress().getPort(); }
        public void close() { release.countDown(); server.stop(0); threads.shutdownNow(); }
    }

    private static void actualSuccessAndRejection() throws Exception {
        try (Receiver receiver = new Receiver()) {
            CountDownLatch done = new CountDownLatch(1);
            try (ComputerConnection check = new ComputerConnection(
                    attempt -> ComputerConnection.probe("127.0.0.1", receiver.port(), TOKEN, attempt),
                    snapshot -> { if (snapshot.phase != ComputerConnection.Phase.CHECKING) done.countDown(); },
                    ComputerConnection.TIMEOUT_MS, 60000)) {
                check.request(true);
                require(done.await(2, TimeUnit.SECONDS), "healthy receiver did not answer");
                require(check.snapshot().phase == ComputerConnection.Phase.CONNECTED, "not connected");
                require(receiver.calls.get() == 1, "not a real single request");
            }
            receiver.wrongProof = true;
            CountDownLatch rejected = new CountDownLatch(1);
            try (ComputerConnection check = new ComputerConnection(
                    attempt -> ComputerConnection.probe("127.0.0.1", receiver.port(), TOKEN, attempt),
                    snapshot -> { if (snapshot.phase == ComputerConnection.Phase.FAILED) rejected.countDown(); },
                    ComputerConnection.TIMEOUT_MS, 60000)) {
                check.request(true);
                require(rejected.await(2, TimeUnit.SECONDS), "wrong peer did not finish");
                require(check.snapshot().phase == ComputerConnection.Phase.FAILED,
                        "HTTP 200 without pairing proof was accepted");
            }
        }
        System.out.println("PASS: real receiver success and invalid pairing proof");
    }

    private static void quietRetryAndNoDuplicate() throws Exception {
        try (Receiver receiver = new Receiver()) {
            receiver.wrongProof = true;
            receiver.blockCall = 2;
            CountDownLatch failed = new CountDownLatch(1);
            CountDownLatch connected = new CountDownLatch(1);
            try (ComputerConnection check = new ComputerConnection(
                    attempt -> ComputerConnection.probe("127.0.0.1", receiver.port(), TOKEN, attempt),
                    snapshot -> {
                        if (snapshot.phase == ComputerConnection.Phase.FAILED) failed.countDown();
                        if (snapshot.phase == ComputerConnection.Phase.CONNECTED) connected.countDown();
                    }, ComputerConnection.TIMEOUT_MS, 500)) {
                check.request(true);
                require(failed.await(2, TimeUnit.SECONDS), "first result was not failure");
                receiver.wrongProof = false;
                require(receiver.blocked.await(2, TimeUnit.SECONDS), "background request never ran");
                require(check.snapshot().phase == ComputerConnection.Phase.FAILED,
                        "quiet retry replaced the finished result with endless progress");
                require(check.snapshot().attemptRunning, "no real background attempt");
                check.request(true);
                require(check.snapshot().phase == ComputerConnection.Phase.CHECKING,
                        "manual request did not reveal the current attempt");
                receiver.release.countDown();
                require(connected.await(2, TimeUnit.SECONDS), "recovered receiver did not connect");
                require(receiver.calls.get() == 2, "manual request duplicated the active retry");
            }
        }
        System.out.println("PASS: finished result survives quiet retry; manual retry has no duplicate");
    }

    private static void tenSecondDeadline() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            AtomicInteger peerPort = new AtomicInteger(server.getLocalPort());
            Thread drip = new Thread(() -> {
                try (Socket peer = server.accept()) {
                    OutputStream output = peer.getOutputStream();
                    output.write("HTTP/1.1 200 OK\r\nX-Slow: ".getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    // A byte arrives often enough that a normal read timeout never expires.
                    for (int index = 0; index < 160; index++) {
                        Thread.sleep(100); output.write('x'); output.flush();
                    }
                } catch (Exception expectedAfterCancellation) { }
            });
            drip.setDaemon(true); drip.start();
            CountDownLatch finished = new CountDownLatch(1);
            long started = System.nanoTime();
            try (ComputerConnection check = new ComputerConnection(
                    attempt -> ComputerConnection.probe("127.0.0.1", peerPort.get(), TOKEN, attempt),
                    snapshot -> { if (snapshot.phase == ComputerConnection.Phase.FAILED) finished.countDown(); },
                    ComputerConnection.TIMEOUT_MS, 60000)) {
                check.request(true);
                require(finished.await(10500, TimeUnit.MILLISECONDS), "progress lasted past the deadline");
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                require(elapsed >= 9400 && elapsed < 10000, "unexpected deadline: " + elapsed);
                require(check.snapshot().phase == ComputerConnection.Phase.FAILED, "deadline did not return failure");
                require(!check.snapshot().attemptRunning, "expired attempt still appeared active");
                try (Receiver recovered = new Receiver()) {
                    peerPort.set(recovered.port());
                    long retryStarted = System.nanoTime();
                    check.request(true);
                    while (check.snapshot().phase == ComputerConnection.Phase.CHECKING &&
                            System.nanoTime() - retryStarted < TimeUnit.SECONDS.toNanos(2)) {
                        Thread.sleep(10);
                    }
                    long retryElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - retryStarted);
                    require(check.snapshot().phase == ComputerConnection.Phase.CONNECTED,
                            "retry was queued behind the expired socket instead of making a real request");
                    require(recovered.calls.get() == 1, "retry did not issue an actual HTTP request");
                    require(retryElapsed < 1000, "recovered receiver took too long: " + retryElapsed);
                    Thread.sleep(250);
                    require(check.snapshot().phase == ComputerConnection.Phase.CONNECTED,
                            "late expired work overwrote the new result");
                    System.out.println("PASS: stalled socket ends at " + elapsed + " ms; actual retry succeeds in " +
                            retryElapsed + " ms; late work cannot overwrite it");
                }
            }
        }
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length > 0 && arguments[0].equals("live")) {
            String token = System.getenv("CATDIAO_PROBE_TOKEN");
            require(token != null && !token.isEmpty(), "live check needs its private environment token");
            CountDownLatch finished = new CountDownLatch(1);
            long started = System.nanoTime();
            try (ComputerConnection check = new ComputerConnection(
                    attempt -> ComputerConnection.probe(arguments[1], 8793, token, attempt),
                    snapshot -> { if (snapshot.phase != ComputerConnection.Phase.CHECKING) finished.countDown(); },
                    ComputerConnection.TIMEOUT_MS, 60000)) {
                check.request(true);
                require(finished.await(10500, TimeUnit.MILLISECONDS), "live check did not finish");
                ComputerConnection.Snapshot result = check.snapshot();
                System.out.println("Live result: " + result.phase + "; elapsed_ms=" +
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                require(result.phase == ComputerConnection.Phase.CONNECTED,
                        "actual receiver did not confirm the pairing: " + result.error);
            }
            return;
        }
        actualSuccessAndRejection();
        quietRetryAndNoDuplicate();
        tenSecondDeadline();
    }
}
