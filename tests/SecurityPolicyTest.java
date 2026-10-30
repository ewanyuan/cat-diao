package com.ewan.wallpaperbridge;

import java.net.InetAddress;

/** Pairing vector, LAN policy, reconnect backoff, and permission copy. */
public final class SecurityPolicyTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        String publicFive = "0".repeat(508) + "0020";
        byte[] shared = PairingExchange.sharedSecret("3", publicFive);
        String token = PairingExchange.token(shared);
        require(token.equals("544f98d341f1e199c37a9dcc769e43ee4477e6eaa1f216234fd75e3d00ecbe0d"), token);
        require(PairingExchange.confirmCode(shared).equals("302041"), PairingExchange.confirmCode(shared));
        require(publicFive.endsWith("0020"), "public");

        byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        RequestAuth.Signed signed = RequestAuth.sign("POST", "/action", token, body, 1_700_000_000L);
        RequestAuth.Verdict verdict = RequestAuth.verify(
                "POST", "/action", token, body, true,
                signed.timestamp, signed.nonce, signed.bodyHash, signed.signature,
                1_700_000_000L, new RequestAuth.NonceWindow());
        require(verdict.ok, verdict.reason);
        RequestAuth.Verdict replay = RequestAuth.verify(
                "POST", "/action", token, body, true,
                signed.timestamp, signed.nonce, signed.bodyHash, signed.signature,
                1_700_000_000L, new RequestAuth.NonceWindow());
        require(replay.ok, "fresh window");
        require(RequestAuth.responseOk(token, signed.nonce, 200, body,
                RequestAuth.signResponse(token, signed.nonce, 200, body)), "response");

        require(LanPolicy.sharesSubnet("192.168.1.20", java.util.List.of("192.168.1.5/24")), "same subnet");
        require(!LanPolicy.sharesSubnet("10.0.0.5", java.util.List.of("192.168.1.5/24")), "other subnet");
        require(!LanPolicy.sharesSubnet("198.18.0.1", java.util.List.of("198.18.0.0/15")), "benchmark range");
        require(!LanPolicy.allows(InetAddress.getByName("198.18.0.1")), "not rfc1918");
        require(LanPolicy.allows(InetAddress.getByName("127.0.0.1")), "loopback");

        ReconnectSchedule schedule = new ReconnectSchedule(10_000, 60_000);
        require(schedule.currentMs() == 10_000, "start");
        schedule.failure();
        require(schedule.currentMs() == 20_000, "20");
        schedule.failure();
        require(schedule.currentMs() == 40_000, "40");
        schedule.failure();
        require(schedule.currentMs() == 60_000, "60");
        schedule.failure();
        require(schedule.currentMs() == 60_000, "cap");
        schedule.success();
        require(schedule.currentMs() == 10_000, "reset");

        require(RoutePolicy.open("GET", "/hello"), "hello");
        require(RoutePolicy.open("POST", "/pair"), "pair");
        require(!RoutePolicy.open("GET", "/screen"), "screen closed");
        require(RoutePolicy.needsScreen("POST", "/action"), "action");
        require(RoutePolicy.needsWriteSettings("POST", "/brightness"), "brightness");
        require(RoutePolicy.pausesWithRemote("POST", "/open"), "open pauses");
        require(!RoutePolicy.pausesWithRemote("GET", "/status"), "status stays");
        require(RoutePolicy.needsScreen("GET", "/ui"), "ui needs screen");
        require(!RoutePolicy.open("GET", "/ui"), "ui closed");
        require(RoutePolicy.pausesWithRemote("POST", "/open-url"), "open-url pauses");
        require(RoutePolicy.pausesWithRemote("POST", "/install"), "install pauses");
        require(RoutePolicy.allowedUrl("https://authenticator.cursor.sh/x"), "https url");
        require(!RoutePolicy.allowedUrl("intent://x#Intent;end"), "no intent url");
        require(!RoutePolicy.allowedUrl("file:///sdcard/a"), "no file url");
        require(!RoutePolicy.allowedUrl("javascript:alert(1)"), "no js url");
        require(RoutePolicy.validPackage("com.android.chrome"), "chrome pkg");
        require(!RoutePolicy.validPackage("com.x;rm"), "bad pkg");
        require(PermissionGuide.mentionsRestrictedSettings(), "restricted settings");
        require(PermissionGuide.PAUSE_REMOTE.equals("暂停遥控"), "pause");
        System.out.println("PASS");
    }
}
