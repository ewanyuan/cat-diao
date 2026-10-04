package com.ewan.wallpaperbridge;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Regression checks use actual sockets and the same cores as the Android service. */
public final class CatDiaoEdgeTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void pairingIdentity() throws Exception {
        AtomicLong clock = new AtomicLong(1000);
        AtomicBoolean paired = new AtomicBoolean(false);
        AtomicReference<PairingGate.Request> saved = new AtomicReference<>();
        PairingGate gate = new PairingGate(clock::get);
        PairingGate.Request a = gate.offer("A", "127.0.0.1", "first", paired::get);
        require(a != null && a == gate.pending(), "first request must be displayed");
        require(gate.offer("B", "127.0.0.2", "second", paired::get) == null,
                "a later request must not replace the displayed computer");
        require(gate.offer("A", "127.0.0.1", "first", paired::get) == a,
                "the same request may retry without invalidating the dialog");
        require(!gate.decide("unseen", true, paired::get, saved::set), "unknown approval must fail");
        require(gate.decide(a.id, true, paired::get, request -> {
            saved.set(request); paired.set(true);
        }), "displayed request can be approved");
        require(saved.get() == a && gate.status(a.id).equals("approved"), "save only the displayed identity");
        require(gate.offer("B", "127.0.0.2", "second", paired::get) == null, "paired device rejects replacement");
        gate.revoke(() -> paired.set(false));
        require(gate.pending() == null && gate.status(a.id) == null, "revocation clears pending identity");
        PairingGate.Request expiring = gate.offer("A", "127.0.0.1", "first", paired::get);
        clock.addAndGet(120000);
        require(gate.pending() == null && gate.status(expiring.id).equals("expired"), "monotonic expiry");
        PairingGate.Request b = gate.offer("B", "127.0.0.2", "second", paired::get);
        require(!gate.decide(expiring.id, true, paired::get, saved::set), "expired dialog cannot approve B");
        require(gate.decide(b.id, false, paired::get, saved::set), "exact request can be rejected");
        require(gate.pending() == null && gate.status(b.id).equals("rejected"), "rejected result is final");

        gate.revoke(() -> paired.set(false));
        PairingGate.Request concurrent = gate.offer("A", "127.0.0.1", "first", paired::get);
        CountDownLatch begin = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread competitor = new Thread(() -> {
            try {
                begin.await();
                for (int i = 0; i < 1000; i++) require(
                        gate.offer("B", "127.0.0.2", "second", paired::get) == null, "concurrent replacement");
            } catch (Throwable error) { failure.set(error); }
        });
        competitor.start();
        begin.countDown();
        require(gate.decide(concurrent.id, true, paired::get, request -> {
            saved.set(request); paired.set(true);
        }), "concurrent approval");
        competitor.join(2000);
        require(!competitor.isAlive() && failure.get() == null && saved.get() == concurrent,
                "concurrent traffic must keep approved identity");
        System.out.println("PASS pairing identity, expiry, revocation and concurrent replacement");
    }

    private static String get(int port) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(1000);
            socket.getOutputStream().write("GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        }
    }

    private static LocalBridgeServer server(int port, long headerMs) {
        return new LocalBridgeServer(port, -1, client -> {
            client.readHeaders();
            client.socket.getOutputStream().write(
                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                            .getBytes(StandardCharsets.US_ASCII));
        }, error -> { throw new AssertionError(error); }, headerMs);
    }

    private static void listenerRecovery() throws Exception {
        long start;
        try (LocalBridgeServer bridge = server(0, 300)) {
            bridge.start();
            try (Socket slow = new Socket("127.0.0.1", bridge.localPort())) {
                slow.setSoTimeout(1000);
                slow.getOutputStream().write("GET /health HTTP/1.1\r\nX-Slow: "
                        .getBytes(StandardCharsets.US_ASCII));
                start = System.nanoTime();
                require(get(bridge.localPort()).endsWith("OK"), "slow headers must not block a healthy client");
                require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 250,
                        "healthy client should run in parallel");
                require(slow.getInputStream().read() == -1, "unfinished headers must close at their deadline");
                require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 800,
                        "header deadline is absolute");
                require(get(bridge.localPort()).endsWith("OK"), "server works after closing slow client");
            }
        }
        ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        int port = occupied.getLocalPort();
        try (LocalBridgeServer bridge = server(port, 500)) {
            boolean failed = false;
            try { bridge.start(); } catch (IOException expected) { failed = true; }
            require(failed && !bridge.isRunning(), "failed bind cannot report running");
            occupied.close();
            bridge.start();
            require(bridge.isRunning() && get(port).endsWith("OK"), "retry must recover from failed bind");
        } finally { occupied.close(); }

        LocalBridgeServer neverStarted = server(0, 500);
        neverStarted.close();
        neverStarted.start();
        require(!neverStarted.isRunning() && neverStarted.localPort() == -1, "destroy before start cannot bind");

        for (int i = 0; i < 8; i++) {
            LocalBridgeServer old = server(0, 500);
            old.start();
            port = old.localPort();
            try (Socket pending = new Socket("127.0.0.1", port)) {
                pending.getOutputStream().write('G');
                old.close();
                try (LocalBridgeServer next = server(port, 500)) {
                    next.start();
                    require(get(port).endsWith("OK") && next.isRunning(), "old close must not stop new owner");
                }
            }
        }
        try (LocalBridgeServer body = new LocalBridgeServer(0, -1, client -> {
            client.readHeaders(); client.watchBody(250);
            client.socket.getInputStream().readNBytes(50);
            client.bodyReceived();
        }, error -> { throw new AssertionError(error); }, 500)) {
            body.start();
            try (Socket incomplete = new Socket("127.0.0.1", body.localPort())) {
                incomplete.setSoTimeout(1000);
                start = System.nanoTime();
                incomplete.getOutputStream().write(
                        "POST /pair HTTP/1.1\r\nContent-Length: 50\r\n\r\nx".getBytes(StandardCharsets.US_ASCII));
                require(incomplete.getInputStream().read() == -1, "unfinished body must close");
                require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 800, "body deadline is absolute");
            }
        }
        System.out.println("PASS parallel requests, stalled headers/body, bind retry and listener ownership");
    }

    private static List<String> captures() {
        List<String> records = new ArrayList<>();
        String text = String.valueOf('\u6587').repeat(30000);
        long timestamp = System.currentTimeMillis();
        for (int i = 0; i < 20; i++) records.add("{\"id\":\"edge-" + i +
                "\",\"created_at\":" + timestamp + ",\"source\":\"test\",\"shared_text\":\"" +
                text + "\",\"url\":\"\"}");
        return records;
    }

    private static void captureBytes(String[] args) throws Exception {
        List<String> records = captures();
        int index = 0, batches = 0, largest = 0;
        while (index < records.size()) {
            CaptureBatch batch = new CaptureBatch();
            int first = index;
            while (index < records.size() && batch.add(records.get(index))) index++;
            require(batch.count() > 0, "a long record must fit");
            byte[] payload = batch.payload();
            require(payload.length <= CaptureBatch.MAX_BYTES && payload.length < 700000,
                    "UTF-8 batch must fit the receiver byte limit");
            largest = Math.max(largest, payload.length);
            if (args.length > 0) {
                ComputerConnection.Attempt attempt = new ComputerConnection.Attempt(9500);
                Timer timer = new Timer(true);
                timer.schedule(new TimerTask() { public void run() { attempt.cancel(); } }, 9500);
                try {
                    ComputerConnection.Reply reply = ComputerConnection.exchange(args[0], Integer.parseInt(args[1]),
                            "POST", "/captures", "X-Phone-Token", "test-pair-secret", payload, attempt);
                    require(reply.code == 200, "real receiver rejected batch: " + reply.code);
                    String accepted = new String(reply.body, StandardCharsets.UTF_8);
                    for (int n = first; n < index; n++) require(accepted.contains("\"edge-" + n + "\""),
                            "real receiver did not acknowledge record " + n);
                } finally { timer.cancel(); attempt.cancel(); }
            }
            batches++;
        }
        require(index == 20 && batches == 4, "20 long Chinese saves must complete in four batches");
        CaptureBatch oversized = new CaptureBatch();
        require(!oversized.add("x".repeat(CaptureBatch.MAX_BYTES)) && oversized.count() == 0,
                "an oversized record must not partially enter a batch");
        System.out.println("PASS UTF-8 batches: records=" + index + ", batches=" + batches + ", max_bytes=" + largest);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { pairingIdentity(); listenerRecovery(); }
        captureBytes(args);
    }
}
