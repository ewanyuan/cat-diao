package com.ewan.wallpaperbridge;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** A Bluetooth channel belongs to the existing app pairing, never just the device name. */
final class BluetoothLink implements AutoCloseable {
    static final UUID SERVICE = UUID.fromString("c96e9124-4d46-49ca-98a2-cad1a0110014");
    interface Host {
        String token();
        boolean wifiOnline();
        JSONArray pending() throws Exception;
        void acknowledge(JSONArray ids) throws Exception;
        void address(String address);
        void changed();
    }
    private final Context context;
    private final Host host;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;
    private volatile BluetoothServerSocket server;
    private volatile BluetoothSocket client;
    private volatile long lastSeen;
    private volatile String state = "starting";
    private Thread worker;

    BluetoothLink(Context context, Host host) { this.context = context; this.host = host; }
    synchronized void start() {
        if (closed || worker != null && worker.isAlive()) return;
        worker = new Thread(this::listen, "catdiao-bluetooth");
        worker.start();
    }
    static boolean permission(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }
    boolean online() { return client != null && lastSeen > 0 && System.currentTimeMillis() - lastSeen < 30000; }
    String state() { return online() ? "connected" : "connected".equals(state) ? "waiting" : state; }
    void disconnect() {
        BluetoothSocket socket;
        synchronized (this) {
            socket = client;
            client = null;
            lastSeen = 0;
            state = closed ? "stopped" : "waiting";
        }
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
        host.changed();
    }
    private void setState(String value) { state = value; host.changed(); }
    private void listen() {
        while (!closed) {
            try {
                if (!permission(context)) { setState("permission_needed"); pause(); continue; }
                BluetoothManager manager = context.getSystemService(BluetoothManager.class);
                BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
                if (adapter == null) { setState("unavailable"); pause(); continue; }
                if (!adapter.isEnabled()) { setState("disabled"); pause(); continue; }
                if (host.token().isEmpty()) { setState("unpaired"); pause(); continue; }
                try (BluetoothServerSocket listener = adapter.listenUsingRfcommWithServiceRecord("猫叼小窝", SERVICE)) {
                    synchronized (this) {
                        if (closed) return;
                        server = listener;
                    }
                    setState("waiting");
                    try (BluetoothSocket socket = listener.accept()) {
                        synchronized (this) {
                            if (closed) return;
                            client = socket;
                        }
                        serve(socket);
                    } finally { disconnect(); }
                } finally { server = null; }
            } catch (Exception error) { if (!closed) setState("waiting"); }
            pause();
        }
    }
    private void pause() { try { Thread.sleep(2000); } catch (InterruptedException ignored) { } }

    private JSONObject read(BluetoothSocket socket, InputStream input) throws Exception {
        // The deadline closes even a peer that keeps dribbling bytes into a frame.
        ScheduledFuture<?> watchdog = deadlines.schedule(() -> {
            try { socket.close(); } catch (Exception ignored) { }
        }, 30, TimeUnit.SECONDS);
        try { return new JSONObject(BluetoothWire.read(input)); }
        finally { watchdog.cancel(false); }
    }
    private void write(BluetoothSocket socket, OutputStream output, JSONObject value) throws Exception {
        ScheduledFuture<?> watchdog = deadlines.schedule(() -> {
            try { socket.close(); } catch (Exception ignored) { }
        }, 30, TimeUnit.SECONDS);
        try { BluetoothWire.write(output, value.toString()); }
        finally { watchdog.cancel(false); }
    }

    private void serve(BluetoothSocket socket) throws Exception {
        String token = host.token();
        if (token.isEmpty()) return;
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();
        JSONObject hello = read(socket, input);
        String nonce = hello.optString("nonce");
        JSONArray addresses = validAddresses(hello.getJSONArray("addresses"));
        if (!nonce.matches("[0-9a-f]{32}") || !BluetoothWire.matches(
                BluetoothWire.proof(token, "hello:" + nonce + ":" + join(addresses)), hello.optString("proof"))) return;
        String other = UUID.randomUUID().toString().replace("-", "");
        JSONArray local = localAddresses();
        write(socket, output, new JSONObject().put("nonce", other).put("addresses", local)
                .put("proof", BluetoothWire.proof(token, "welcome:" + nonce + ":" + other + ":" + join(local))));
        String key = BluetoothWire.proof(token, "session:" + nonce + ":" + other);
        updateAddress(addresses);
        Set<String> sent = new HashSet<>();
        long sequence = 0;
        while (!closed && token.equals(host.token())) {
            JSONObject request = read(socket, input);
            String payload = request.getString("payload");
            long next = request.getLong("sequence");
            if (next != ++sequence || !BluetoothWire.matches(
                    BluetoothWire.proof(key, "pc:" + sequence + ":" + payload), request.optString("proof"))) return;
            if (!token.equals(host.token())) return;
            JSONObject body = new JSONObject(payload);
            JSONArray acknowledged = body.optJSONArray("accepted");
            JSONArray accepted = new JSONArray();
            if (acknowledged != null && acknowledged.length() <= 20) for (int i = 0; i < acknowledged.length(); i++) {
                String id = acknowledged.optString(i);
                if (sent.contains(id)) accepted.put(id);
            }
            if (accepted.length() > 0) host.acknowledge(accepted);
            updateAddress(validAddresses(body.getJSONArray("addresses")));
            lastSeen = System.currentTimeMillis();
            setState("connected");
            JSONArray items = host.wifiOnline() ? new JSONArray() : host.pending();
            sent.clear();
            for (int i = 0; i < items.length(); i++) sent.add(items.getJSONObject(i).getString("id"));
            String result = new JSONObject().put("addresses", localAddresses()).put("items", items).toString();
            write(socket, output, new JSONObject().put("sequence", sequence).put("payload", result)
                    .put("proof", BluetoothWire.proof(key, "phone:" + sequence + ":" + result)));
        }
    }

    private static JSONArray validAddresses(JSONArray input) throws Exception {
        if (input.length() > 16) throw new IllegalArgumentException("Too many addresses");
        JSONArray result = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < input.length(); i++) {
            String text = input.getString(i);
            if (!text.matches("[0-9.]{7,15}")) continue;
            InetAddress address = InetAddress.getByName(text);
            if (address instanceof Inet4Address && address.isSiteLocalAddress() && !address.isLoopbackAddress()
                    && !address.isLinkLocalAddress() && seen.add(address.getHostAddress())) result.put(address.getHostAddress());
        }
        return result;
    }
    static JSONArray localAddresses() throws Exception {
        JSONArray addresses = new JSONArray();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) continue;
            for (InterfaceAddress item : network.getInterfaceAddresses()) {
                InetAddress address = item.getAddress();
                if (address instanceof Inet4Address && address.isSiteLocalAddress() && !address.isLinkLocalAddress())
                    addresses.put(address.getHostAddress());
            }
        }
        return validAddresses(addresses);
    }
    private void updateAddress(JSONArray addresses) throws Exception {
        if (host.wifiOnline()) return;
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) continue;
            for (InterfaceAddress local : network.getInterfaceAddresses()) {
                if (!(local.getAddress() instanceof Inet4Address)) continue;
                int prefix = local.getNetworkPrefixLength();
                if (prefix < 0 || prefix > 32) continue;
                int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
                for (int i = 0; i < addresses.length(); i++) {
                    String candidate = addresses.getString(i);
                    if ((ipv4(local.getAddress()) & mask) == (ipv4(InetAddress.getByName(candidate)) & mask)) {
                        host.address(candidate); return;
                    }
                }
            }
        }
    }
    private static int ipv4(InetAddress address) {
        byte[] bytes = address.getAddress();
        return (bytes[0] & 255) << 24 | (bytes[1] & 255) << 16 | (bytes[2] & 255) << 8 | bytes[3] & 255;
    }
    private static String join(JSONArray addresses) throws Exception {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < addresses.length(); i++) { if (i > 0) result.append(','); result.append(addresses.getString(i)); }
        return result.toString();
    }
    @Override public void close() {
        BluetoothServerSocket listener;
        synchronized (this) {
            closed = true;
            listener = server;
            server = null;
        }
        disconnect();
        if (listener != null) try { listener.close(); } catch (Exception ignored) { }
        if (worker != null) worker.interrupt();
        deadlines.shutdownNow();
    }
}
