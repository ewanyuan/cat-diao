package com.ewan.wallpaperbridge;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Keeps the displayed request, approval and persisted computer identity together. */
final class PairingGate {
    private static final long VALID_MS = 120000;
    private final LongSupplier clock;
    private Request request;

    static final class Request {
        final String id = UUID.randomUUID().toString();
        final String name;
        final String address;
        final String token;
        final long created;
        volatile String outcome = "pending";

        Request(String name, String address, String token, long created) {
            this.name = name;
            this.address = address;
            this.token = token;
            this.created = created;
        }
    }

    PairingGate() { this(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime())); }
    PairingGate(LongSupplier clock) { this.clock = clock; }

    private void expire() {
        if (request != null && request.outcome.equals("pending")) {
            long age = clock.getAsLong() - request.created;
            if (age < 0 || age >= VALID_MS) request.outcome = "expired";
        }
    }

    synchronized Request offer(String name, String address, String token, BooleanSupplier paired) {
        if (paired.getAsBoolean()) return null;
        expire();
        if (request != null && request.outcome.equals("pending")) {
            return request.name.equals(name) && request.address.equals(address) && request.token.equals(token)
                    ? request : null;
        }
        request = new Request(name, address, token, clock.getAsLong());
        return request;
    }

    synchronized Request pending() {
        expire();
        return request != null && request.outcome.equals("pending") ? request : null;
    }

    synchronized String status(String id) {
        expire();
        return request != null && request.id.equals(id) ? request.outcome : null;
    }

    synchronized boolean decide(String displayedId, boolean allow, BooleanSupplier paired,
                                Consumer<Request> save) {
        expire();
        if (request == null || !request.id.equals(displayedId) || !request.outcome.equals("pending")
                || paired.getAsBoolean()) return false;
        if (allow) save.accept(request);
        request.outcome = allow ? "approved" : "rejected";
        return true;
    }

    synchronized void revoke(Runnable clear) {
        clear.run();
        request = null;
    }
}
