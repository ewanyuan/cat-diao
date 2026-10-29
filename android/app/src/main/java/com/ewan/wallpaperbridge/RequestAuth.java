package com.ewan.wallpaperbridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC on every authenticated request. The pairing token itself is never sent. */
public final class RequestAuth {
    public static final int WINDOW_SECONDS = 60;
    public static final int NONCE_HEX_LENGTH = 32;

    public static final class Signed {
        public final String nonce;
        public final String timestamp;
        public final String bodyHash;
        public final String signature;
        public final Map<String, String> headers;

        Signed(String nonce, String timestamp, String bodyHash, String signature) {
            this.nonce = nonce;
            this.timestamp = timestamp;
            this.bodyHash = bodyHash;
            this.signature = signature;
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("X-CatDiao-Ts", timestamp);
            fields.put("X-CatDiao-Nonce", nonce);
            fields.put("X-CatDiao-Body", bodyHash);
            fields.put("X-CatDiao-Sig", signature);
            this.headers = fields;
        }
    }

    public static final class Verdict {
        public final boolean ok;
        public final String reason;
        public final String nonce;
        public final String bodyHash;

        Verdict(boolean ok, String reason, String nonce, String bodyHash) {
            this.ok = ok;
            this.reason = reason;
            this.nonce = nonce;
            this.bodyHash = bodyHash;
        }

        static Verdict no(String reason) { return new Verdict(false, reason, "", ""); }
    }

    public static final class NonceWindow {
        private final Map<String, Long> seen = new LinkedHashMap<>();
        private final int limit;

        public NonceWindow() { this(4096); }
        public NonceWindow(int limit) { this.limit = limit; }

        public synchronized boolean remember(String nonce, long nowSeconds) {
            seen.entrySet().removeIf(entry -> entry.getValue() < nowSeconds);
            if (seen.containsKey(nonce)) return false;
            if (seen.size() >= limit) {
                String oldest = seen.keySet().iterator().next();
                seen.remove(oldest);
            }
            seen.put(nonce, nowSeconds + WINDOW_SECONDS);
            return true;
        }
    }

    private RequestAuth() { }

    public static String bodyHash(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body);
            return hex(digest);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    public static String canonicalRequest(String method, String path, String timestamp,
                                          String nonce, String digest) {
        return "request\n" + method.toUpperCase(Locale.ROOT) + "\n" + path + "\n"
                + timestamp + "\n" + nonce + "\n" + digest;
    }

    public static String canonicalResponse(String nonce, int status, String digest) {
        return "response\n" + nonce + "\n" + status + "\n" + digest;
    }

    public static Signed sign(String method, String path, String token, byte[] body, long nowSeconds) {
        String digest = bodyHash(body);
        String timestamp = Long.toString(nowSeconds);
        byte[] random = new byte[16];
        new java.security.SecureRandom().nextBytes(random);
        String nonce = hex(random);
        String signature = hmac(token, canonicalRequest(method, path, timestamp, nonce, digest));
        return new Signed(nonce, timestamp, digest, signature);
    }

    public static String signResponse(String token, String nonce, int status, byte[] body) {
        return hmac(token, canonicalResponse(nonce, status, bodyHash(body)));
    }

    public static boolean responseOk(String token, String nonce, int status, byte[] body, String signature) {
        if (token == null || token.isEmpty() || nonce == null || signature == null) return false;
        String expected = signResponse(token, nonce, status, body);
        return constantTimeEquals(expected, signature.trim().toLowerCase(Locale.ROOT));
    }

    public static Verdict verify(String method, String path, String token, byte[] body, boolean checkBody,
                                 String timestamp, String nonce, String digest, String signature,
                                 long nowSeconds, NonceWindow window) {
        if (token == null || token.isEmpty()) return Verdict.no("unpaired");
        if (timestamp == null || nonce == null || digest == null || signature == null) return Verdict.no("malformed");
        timestamp = timestamp.trim();
        nonce = nonce.trim().toLowerCase(Locale.ROOT);
        digest = digest.trim().toLowerCase(Locale.ROOT);
        signature = signature.trim().toLowerCase(Locale.ROOT);
        if (!timestamp.chars().allMatch(Character::isDigit) || nonce.length() != NONCE_HEX_LENGTH
                || digest.length() != 64 || signature.length() != 64) return Verdict.no("malformed");
        if (!isHex(nonce) || !isHex(digest) || !isHex(signature)) return Verdict.no("malformed");
        long stamp;
        try { stamp = Long.parseLong(timestamp); }
        catch (NumberFormatException error) { return Verdict.no("malformed"); }
        if (Math.abs(nowSeconds - stamp) > WINDOW_SECONDS) return Verdict.no("expired");
        String expected = hmac(token, canonicalRequest(method, path, timestamp, nonce, digest));
        if (!constantTimeEquals(expected, signature)) return Verdict.no("signature");
        if (window != null && !window.remember(nonce, nowSeconds)) return Verdict.no("replay");
        if (checkBody && !constantTimeEquals(bodyHash(body), digest))
            return new Verdict(false, "body", nonce, digest);
        return new Verdict(true, "", nonce, digest);
    }

    public static String header(Map<String, String> headers, String name) {
        if (headers == null) return "";
        String direct = headers.get(name);
        if (direct == null) direct = headers.get(name.toLowerCase(Locale.ROOT));
        if (direct != null) return direct;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null)
                return entry.getValue();
        }
        return "";
    }

    private static boolean isHex(String value) {
        for (int index = 0; index < value.length(); index++) {
            char valueChar = value.charAt(index);
            boolean digit = valueChar >= '0' && valueChar <= '9';
            boolean lower = valueChar >= 'a' && valueChar <= 'f';
            if (!digit && !lower) return false;
        }
        return true;
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null || left.length() != right.length()) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    static String hmac(String token, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return text.toString();
    }
}
