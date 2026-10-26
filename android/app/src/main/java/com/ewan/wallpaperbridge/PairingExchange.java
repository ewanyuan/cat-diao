package com.ewan.wallpaperbridge;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/** RFC 3526 group 14 exchange. The 6-digit code is shown locally and is not sent. */
public final class PairingExchange {
    static final String PRIME_HEX =
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1"
                    + "29024E088A67CC74020BBEA63B139B22514A08798E3404DD"
                    + "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245"
                    + "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
                    + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D"
                    + "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F"
                    + "83655D23DCA3AD961C62F356208552BB9ED529077096966D"
                    + "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
                    + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9"
                    + "DE2BCBF6955817183995497CEA956AE515D2261898FA0510"
                    + "15728E5A8AACAA68FFFFFFFFFFFFFFFF";
    public static final BigInteger PRIME = new BigInteger(PRIME_HEX, 16);
    public static final BigInteger GENERATOR = BigInteger.TWO;
    public static final int SECRET_BYTES = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final class KeyPair {
        public final String privateHex;
        public final String publicHex;

        KeyPair(String privateHex, String publicHex) {
            this.privateHex = privateHex;
            this.publicHex = publicHex;
        }
    }

    private PairingExchange() { }

    public static boolean validPublic(String value) {
        if (value == null || value.length() != SECRET_BYTES * 2) return false;
        for (int index = 0; index < value.length(); index++) {
            char item = value.charAt(index);
            if (!((item >= '0' && item <= '9') || (item >= 'a' && item <= 'f'))) return false;
        }
        BigInteger number = new BigInteger(value, 16);
        return number.compareTo(BigInteger.TWO) >= 0
                && number.compareTo(PRIME.subtract(BigInteger.TWO)) <= 0;
    }

    public static KeyPair generate() {
        byte[] seed = new byte[32];
        RANDOM.nextBytes(seed);
        BigInteger secret = new BigInteger(1, seed);
        if (secret.compareTo(BigInteger.TWO) < 0) secret = BigInteger.TWO;
        BigInteger publicKey = GENERATOR.modPow(secret, PRIME);
        return new KeyPair(secret.toString(16), pad(publicKey));
    }

    public static byte[] sharedSecret(String privateHex, String peerPublicHex) {
        if (!validPublic(peerPublicHex)) throw new IllegalArgumentException("peer public key rejected");
        BigInteger secret;
        try { secret = new BigInteger(privateHex, 16); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("private key rejected"); }
        if (secret.compareTo(BigInteger.TWO) < 0 || secret.compareTo(PRIME.subtract(BigInteger.ONE)) >= 0)
            throw new IllegalArgumentException("private key rejected");
        BigInteger shared = new BigInteger(peerPublicHex, 16).modPow(secret, PRIME);
        if (shared.compareTo(BigInteger.TWO) < 0) throw new IllegalArgumentException("shared secret rejected");
        return fixed(shared);
    }

    public static String token(byte[] shared) {
        return sha256(prefix("catdiao-token-v1\n", shared));
    }

    public static String confirmCode(byte[] shared) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(prefix("catdiao-confirm-v1\n", shared));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        long unsigned = ((long) (digest[0] & 0xff) << 24) | ((long) (digest[1] & 0xff) << 16)
                | ((digest[2] & 0xff) << 8) | (digest[3] & 0xff);
        return String.format(Locale.ROOT, "%06d", unsigned % 1_000_000L);
    }

    private static byte[] prefix(String label, byte[] shared) {
        byte[] labelBytes = label.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] joined = new byte[labelBytes.length + shared.length];
        System.arraycopy(labelBytes, 0, joined, 0, labelBytes.length);
        System.arraycopy(shared, 0, joined, labelBytes.length, shared.length);
        return joined;
    }

    private static String sha256(byte[] input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
            StringBuilder text = new StringBuilder(digest.length * 2);
            for (byte value : digest) text.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return text.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String pad(BigInteger value) {
        String hex = value.toString(16);
        StringBuilder padded = new StringBuilder(SECRET_BYTES * 2);
        for (int index = hex.length(); index < SECRET_BYTES * 2; index++) padded.append('0');
        padded.append(hex);
        return padded.toString();
    }

    private static byte[] fixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] fixed = new byte[SECRET_BYTES];
        int offset = raw.length == SECRET_BYTES + 1 && raw[0] == 0 ? 1 : 0;
        int length = raw.length - offset;
        if (length > SECRET_BYTES) throw new IllegalArgumentException("shared secret rejected");
        System.arraycopy(raw, offset, fixed, SECRET_BYTES - length, length);
        return fixed;
    }
}
