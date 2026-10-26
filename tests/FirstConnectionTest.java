package com.ewan.wallpaperbridge;

public final class FirstConnectionTest {
    public static void main(String[] args) {
        FirstConnection.Screen fresh = FirstConnection.present(facts(false, false, false, "", "", "", ""));
        require(fresh.state == FirstConnection.State.NOT_PAIRED, "start unpaired");
        require(fresh.sentence.length() < 40, "one short sentence");
        require("start".equals(fresh.primaryAction), "one primary action");
        require(!fresh.bluetoothEnabled, "bluetooth stays off");
        require(fresh.bluetoothReason.equals(FirstConnection.BLUETOOTH_LOCKED), "one-line reason");
        require(fresh.secondary.isEmpty(), "no dead-end second button");

        FirstConnection.Screen waiting = FirstConnection.present(
                facts(false, true, false, "", "", "", "http://10.69.1.253:8767"));
        require(waiting.state == FirstConnection.State.WAITING, "waiting");
        require(waiting.copyText.equals(FirstConnection.AI_LINE), "copyable line");
        require(waiting.copyText.contains(FirstConnection.PC_COMMAND), "points at run.ps1 pair");
        require(waiting.address.equals("http://10.69.1.253:8767"), "phone address");
        require(waiting.showQr, "qr when the computer must be told the address");
        require(waiting.how.contains("run.ps1 pair") && waiting.how.contains("允许"), "how expands");
        require(!waiting.bluetoothEnabled, "still no bluetooth");

        FirstConnection.Screen pairing = FirstConnection.present(
                facts(false, true, true, "302041", "书房", "", "http://10.69.1.253:8767"));
        require(pairing.state == FirstConnection.State.PAIRING, "code wins over help");
        require(pairing.code.equals("302041"), "big code");
        require("allow".equals(pairing.primaryAction) && "deny".equals(pairing.secondaryAction), "allow or deny");
        require(pairing.bluetoothReason.isEmpty(), "pairing hides bluetooth");

        FirstConnection.Screen paired = FirstConnection.present(
                facts(true, false, false, "", "书房电脑", "刚刚送到", ""));
        require(paired.state == FirstConnection.State.PAIRED && paired.green, "paired is green");
        require(paired.sentence.contains("书房电脑"), "pc name");
        require(paired.detail.contains("刚刚送到"), "last sync");
        require(paired.bluetoothEnabled && "bluetooth".equals(paired.secondaryAction), "bluetooth only after pairing");

        FirstConnection.Screen problems = FirstConnection.present(
                facts(false, true, true, "", "", "", "http://10.69.1.253:8767"));
        require(problems.state == FirstConnection.State.PROBLEMS, "problems");
        require(problems.causes.length == 3, "three causes");
        String joined = String.join("\n", problems.causes);
        require(joined.contains("同一个 Wi-Fi") && joined.contains("热点") && joined.contains("公用"), "plain causes");
        require("retry".equals(problems.primaryAction), "back to waiting");
        require(problems.showQr && problems.showAddress, "address fallback");

        boolean[][] qr = QrCode.encode("http://10.69.1.253:8767");
        require(qr.length == 25 && qr[0].length == 25, "version 2 size");
        require(qr[0][0] && qr[0][6] && !qr[0][7], "finder");
        require(rows(qr).equals(SAMPLE), "address qr matches the known mask");
        System.out.println("PASS first connection steps and address qr");
    }

    private static final String SAMPLE = String.join("\n",
            "1111111001010001101111111",
            "1000001010010001101000001",
            "1011101000011000001011101",
            "1011101001010001101011101",
            "1011101011111101101011101",
            "1000001001001001001000001",
            "1111111010101010101111111",
            "0000000001101100000000000",
            "1010101001100110000010010",
            "1001010100001010011100001",
            "0100011100101010110010111",
            "1100110010100100111100010",
            "0010111110010111101101011",
            "0010000101010110001101001",
            "1011111001100100011110111",
            "0101000110010111011101010",
            "1001101000101110111111000",
            "0000000010111110100011111",
            "1111111001011101101010011",
            "1000001001101101100011001",
            "1011101011111110111110011",
            "1011101000110111110010100",
            "1011101010001101010011001",
            "1000001001010111111011010",
            "1111111010101010111100011");

    private static String rows(boolean[][] qr) {
        StringBuilder out = new StringBuilder();
        for (int row = 0; row < qr.length; row++) {
            if (row > 0) out.append('\n');
            for (boolean cell : qr[row]) out.append(cell ? '1' : '0');
        }
        return out.toString();
    }

    private static FirstConnection.Facts facts(boolean paired, boolean waiting, boolean help, String code,
                                               String name, String sync, String address) {
        return new FirstConnection.Facts(paired, waiting, help, code, name, sync, address);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
