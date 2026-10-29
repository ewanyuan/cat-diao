package com.ewan.wallpaperbridge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Byte-mode QR, error correction M, mask 0. Enough for a phone address. */
public final class QrCode {
    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];
    private static final int[] DATA_CODEWORDS = {0, 16, 28, 44, 64};
    private static final int[] ECC_CODEWORDS = {0, 10, 16, 26, 18};
    private static final int[] BLOCKS = {0, 1, 1, 1, 2};
    private static final int[][] ALIGN = {
            {}, {}, {6, 18}, {6, 22}, {6, 26}
    };

    static {
        int value = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = value;
            LOG[value] = i;
            value <<= 1;
            if ((value & 0x100) != 0) value ^= 0x11d;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    }

    private QrCode() {}

    public static boolean[][] encode(String text) {
        byte[] payload = text.getBytes(StandardCharsets.ISO_8859_1);
        if (payload.length != text.length()) throw new IllegalArgumentException("address");
        int version = versionFor(payload.length);
        int size = 17 + 4 * version;
        Boolean[][] grid = new Boolean[size][size];
        finder(grid, 0, 0);
        finder(grid, 0, size - 7);
        finder(grid, size - 7, 0);
        for (int i = 8; i < size - 8; i++) {
            boolean bit = i % 2 == 0;
            if (grid[6][i] == null) grid[6][i] = bit;
            if (grid[i][6] == null) grid[i][6] = bit;
        }
        for (int row : ALIGN[version]) {
            for (int col : ALIGN[version]) {
                if (grid[row][col] != null) continue;
                for (int y = -2; y <= 2; y++) {
                    for (int x = -2; x <= 2; x++) {
                        boolean edge = Math.abs(y) == 2 || Math.abs(x) == 2;
                        grid[row + y][col + x] = edge || (y == 0 && x == 0);
                    }
                }
            }
        }
        grid[4 * version + 9][8] = true;
        for (int i = 0; i < 9; i++) {
            if (grid[i][8] == null) grid[i][8] = false;
            if (grid[8][i] == null) grid[8][i] = false;
            if (i > 0) {
                if (grid[size - i][8] == null) grid[size - i][8] = false;
                if (grid[8][size - i] == null) grid[8][size - i] = false;
            }
        }
        List<Integer> bits = dataBits(payload, version);
        int index = 0;
        for (int step = size - 1; step > 0; step -= 2) {
            int right = step <= 6 ? step - 1 : step;
            for (int vertical = 0; vertical < size; vertical++) {
                for (int z = 0; z < 2; z++) {
                    int col = right - z;
                    boolean upwards = (right & 2) == 0;
                    if (col < 6) upwards = !upwards;
                    int row = upwards ? size - 1 - vertical : vertical;
                    if (grid[row][col] == null) {
                        int bit = index < bits.size() ? bits.get(index) : 0;
                        index++;
                        if ((row + col) % 2 == 0) bit ^= 1;
                        grid[row][col] = bit == 1;
                    }
                }
            }
        }
        int format = formatBits(0);
        int voffset = 0;
        int hoffset = 0;
        for (int i = 0; i < 8; i++) {
            boolean vbit = ((format >> i) & 1) == 1;
            boolean hbit = ((format >> (14 - i)) & 1) == 1;
            if (i == 6) {
                voffset += 1;
                hoffset = 1;
            }
            grid[i + voffset][8] = vbit;
            grid[8][i + hoffset] = hbit;
            grid[8][size - 1 - i] = vbit;
            grid[size - 1 - i][8] = hbit;
        }
        grid[size - 8][8] = true;
        boolean[][] drawn = new boolean[size][size];
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) drawn[row][col] = Boolean.TRUE.equals(grid[row][col]);
        }
        return drawn;
    }

    private static int versionFor(int length) {
        for (int version = 1; version <= 4; version++) {
            if (4 + 8 + length * 8 <= DATA_CODEWORDS[version] * 8) return version;
        }
        throw new IllegalArgumentException("address");
    }

    private static List<Integer> dataBits(byte[] payload, int version) {
        int capacity = DATA_CODEWORDS[version] * 8;
        List<Integer> bits = new ArrayList<>();
        append(bits, 0x4, 4);
        append(bits, payload.length, 8);
        for (byte value : payload) append(bits, value & 0xff, 8);
        int terminator = Math.min(4, capacity - bits.size());
        for (int i = 0; i < terminator; i++) bits.add(0);
        if (bits.size() % 8 == 0 && bits.size() < capacity) {
            for (int i = 0; i < 8; i++) bits.add(0);
        }
        while (bits.size() % 8 != 0) bits.add(0);
        int pad = 0;
        while (bits.size() < capacity) {
            append(bits, pad % 2 == 0 ? 0xec : 0x11, 8);
            pad++;
        }
        int[] bytes = new int[DATA_CODEWORDS[version]];
        for (int i = 0; i < bytes.length; i++) {
            int value = 0;
            for (int bit = 0; bit < 8; bit++) value = (value << 1) | bits.get(i * 8 + bit);
            bytes[i] = value;
        }
        int[] coded = interleave(bytes, version);
        List<Integer> stream = new ArrayList<>();
        for (int value : coded) append(stream, value, 8);
        return stream;
    }

    private static void append(List<Integer> bits, int value, int width) {
        for (int shift = width - 1; shift >= 0; shift--) bits.add((value >> shift) & 1);
    }

    private static int[] interleave(int[] data, int version) {
        int blocks = BLOCKS[version];
        int per = DATA_CODEWORDS[version] / blocks;
        int ecc = ECC_CODEWORDS[version];
        int[][] parts = new int[blocks][];
        for (int block = 0; block < blocks; block++) {
            int[] chunk = new int[per];
            System.arraycopy(data, block * per, chunk, 0, per);
            int[] remainder = remainder(chunk, ecc);
            parts[block] = new int[per + ecc];
            System.arraycopy(chunk, 0, parts[block], 0, per);
            System.arraycopy(remainder, 0, parts[block], per, ecc);
        }
        int[] out = new int[DATA_CODEWORDS[version] + ecc * blocks];
        int cursor = 0;
        for (int i = 0; i < per; i++) for (int[] part : parts) out[cursor++] = part[i];
        for (int i = 0; i < ecc; i++) for (int[] part : parts) out[cursor++] = part[per + i];
        return out;
    }

    private static int[] generator(int count) {
        int[] poly = {1};
        for (int i = 0; i < count; i++) {
            int[] next = new int[poly.length + 1];
            for (int j = 0; j < poly.length; j++) {
                next[j] ^= poly[j];
                next[j + 1] ^= poly[j] == 0 ? 0 : EXP[(LOG[poly[j]] + i) % 255];
            }
            poly = next;
        }
        return poly;
    }

    private static int[] remainder(int[] data, int count) {
        int[] gen = generator(count);
        int[] buffer = new int[data.length + count];
        System.arraycopy(data, 0, buffer, 0, data.length);
        for (int i = 0; i < data.length; i++) {
            int factor = buffer[i];
            if (factor == 0) continue;
            for (int j = 0; j < gen.length; j++) {
                if (gen[j] == 0) continue;
                buffer[i + j] ^= EXP[(LOG[factor] + LOG[gen[j]]) % 255];
            }
        }
        int[] out = new int[count];
        System.arraycopy(buffer, data.length, out, 0, count);
        return out;
    }

    private static int formatBits(int mask) {
        int rem = mask << 10;
        for (int i = 14; i >= 10; i--) {
            if (((rem >> i) & 1) != 0) rem ^= 0x537 << (i - 10);
        }
        return ((mask << 10) | (rem & 0x3ff)) ^ 0x5412;
    }

    private static void finder(Boolean[][] grid, int row, int col) {
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 7; x++) {
                boolean border = y == 0 || y == 6 || x == 0 || x == 6;
                boolean core = y >= 2 && y <= 4 && x >= 2 && x <= 4;
                grid[row + y][col + x] = border || core;
            }
        }
        for (int y = -1; y <= 7; y++) {
            for (int x = -1; x <= 7; x++) {
                int rr = row + y;
                int cc = col + x;
                if (rr >= 0 && cc >= 0 && rr < grid.length && cc < grid.length && grid[rr][cc] == null) {
                    grid[rr][cc] = false;
                }
            }
        }
    }
}
