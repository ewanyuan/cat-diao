package com.ewan.wallpaperbridge;

public final class FirstConnectionTest {
    private static final FirstConnection.Copy COPY = new FirstConnection.Copy() {
        @Override public String t(String key) { return key; }
        @Override public String f(String key, Object... args) {
            StringBuilder out = new StringBuilder(key);
            for (Object arg : args) out.append('|').append(arg);
            return out.toString();
        }
    };

    public static void main(String[] args) {
        require(FirstConnection.commandLine().equals("scripts\\run.ps1 pair"), "backslash survives in the command");
        require(FirstConnection.commandLine().indexOf('\\') == 7, "one real backslash");
        require(FirstConnection.classify(true, false, false, false, true) == FirstConnection.Link.WIFI, "wifi");
        require(FirstConnection.classify(false, true, false, false, false) == FirstConnection.Link.CELLULAR, "cellular");
        require(FirstConnection.classify(false, false, false, false, false) == FirstConnection.Link.OFFLINE, "offline");
        require(FirstConnection.classify(true, false, true, false, true) == FirstConnection.Link.VPN, "vpn wins");
        require(FirstConnection.classify(true, false, false, true, true) == FirstConnection.Link.CAPTIVE, "captive");
        require(FirstConnection.classify(true, false, false, false, false) == FirstConnection.Link.NO_LAN, "no lan address");

        FirstConnection.Screen fresh = FirstConnection.present(facts(false, false, false, false, false, false,
                "", "", "", "http://10.69.1.253:8767", FirstConnection.Link.WIFI, true, 0), COPY);
        require(fresh.state == FirstConnection.State.GUIDE, "guide when nothing has been tried");
        require(fresh.sentence.equals("conn_not_paired_body"), "one install sentence");
        require("copy".equals(fresh.primaryAction), "primary copies the line");
        require("qr".equals(fresh.secondaryAction), "qr is the only secondary");
        require(fresh.bluetoothReason.isEmpty() && !fresh.bluetoothEnabled, "no bluetooth nag");
        require(fresh.copyText.contains("http://10.69.1.253:8767"), "copy embeds the address");
        require(!fresh.copyText.contains(FirstConnection.PC_COMMAND), "command stays off the guide");
        require(fresh.commandLine.isEmpty() && fresh.how.isEmpty(), "help stays closed until failure");
        require(!fresh.showQr, "qr waits for the secondary tap");
        require(fresh.detail.equals("conn_wifi_ok"), "wifi was detected");

        FirstConnection.Screen cellular = FirstConnection.present(facts(false, false, false, false, false, false,
                "", "", "", "", FirstConnection.Link.CELLULAR, true, 0), COPY);
        require(cellular.state == FirstConnection.State.GUIDE, "warn before starting");
        require(cellular.sentence.equals("conn_cause_cellular"), "one cellular cause");
        require("recheck".equals(cellular.primaryAction) && cellular.secondary.isEmpty(), "one recheck button");
        require(cellular.how.isEmpty() && cellular.copyText.isEmpty(), "no command before a try");

        FirstConnection.Screen qr = FirstConnection.present(facts(false, false, true, true, false, false,
                "", "", "", "http://10.69.1.253:8767", FirstConnection.Link.WIFI, true, 1000), COPY);
        require(qr.state == FirstConnection.State.GUIDE && qr.showQr, "qr stays on the guide");
        require(qr.sentence.equals("conn_not_paired_body"), "qr does not replace the sentence");

        FirstConnection.Screen waiting = FirstConnection.present(facts(false, true, false, true, false, false,
                "", "", "", "http://10.69.1.253:8767", FirstConnection.Link.WIFI, true, 12_000), COPY);
        require(waiting.state == FirstConnection.State.WAITING, "waiting");
        require(waiting.title.equals("conn_waiting_title"), "waiting title");
        require(waiting.sentence.equals("conn_waiting_body|12"), "elapsed seconds");
        require(waiting.detail.equals("conn_code_pending"), "where the code will appear");
        require(waiting.primary.isEmpty() && waiting.secondary.isEmpty(), "no extra choices while waiting");
        require(waiting.code.isEmpty() && waiting.how.isEmpty(), "no command and no fake code");
        require(!waiting.copyText.contains(FirstConnection.PC_COMMAND), "command not in the wait");

        FirstConnection.Screen pairing = FirstConnection.present(facts(false, true, false, true, true, true,
                "302041", "书房", "", "http://10.69.1.253:8767", FirstConnection.Link.CELLULAR, false, 90_000), COPY);
        require(pairing.state == FirstConnection.State.PAIRING, "code wins over failure");
        require(pairing.title.equals("conn_waiting_title"), "code stays on the waiting screen");
        require(pairing.code.equals("302041"), "big code");
        require("allow".equals(pairing.primaryAction) && "deny".equals(pairing.secondaryAction), "allow or deny");
        require(pairing.bluetoothReason.isEmpty(), "pairing hides bluetooth");

        FirstConnection.Screen paired = FirstConnection.present(facts(true, false, false, false, false, false,
                "", "书房电脑", "刚刚送到", "", FirstConnection.Link.WIFI, true, 0), COPY);
        require(paired.state == FirstConnection.State.PAIRED && paired.green, "paired is green");
        require(paired.title.equals("conn_paired_title"), "success title");
        require(paired.sentence.contains("书房电脑"), "pc name");
        require(paired.detail.contains("刚刚送到"), "last sync");
        require("try".equals(paired.primaryAction) && "done".equals(paired.secondaryAction), "try then done");
        require(paired.bluetoothReason.isEmpty(), "no bluetooth footer after success");

        FirstConnection.Screen earlyServer = FirstConnection.present(facts(false, true, false, true, false, false,
                "", "", "", "http://10.69.1.253:8767", FirstConnection.Link.WIFI, false, 1000), COPY);
        require(earlyServer.state == FirstConnection.State.WAITING, "server grace");
        FirstConnection.Screen server = FirstConnection.present(facts(false, true, false, true, false, false,
                "", "", "", "http://10.69.1.253:8767", FirstConnection.Link.WIFI, false, 5_000), COPY);
        require(server.state == FirstConnection.State.PROBLEMS && server.sentence.equals("conn_cause_server"), "server down");

        FirstConnection.Screen vpn = FirstConnection.present(facts(false, true, false, true, false, false,
                "", "", "", "http://10.69.1.253:8767", FirstConnection.Link.VPN, true, 1000), COPY);
        require(vpn.sentence.equals("conn_cause_vpn") && vpn.causes.length == 0, "one vpn cause");

        FirstConnection.Screen expired = FirstConnection.present(facts(false, true, false, true, true, false,
                "", "", "", "http://10.69.1.253:8767", FirstConnection.Link.WIFI, true, 1000), COPY);
        require(expired.sentence.equals("conn_cause_timeout"), "expired code");

        FirstConnection.Screen missed = FirstConnection.present(facts(false, true, false, true, false, false,
                "", "", "", "http://10.69.1.253:8767", FirstConnection.Link.WIFI, true, FirstConnection.GIVE_UP_MS), COPY);
        require(missed.state == FirstConnection.State.PROBLEMS, "timeout");
        require(missed.sentence.equals("conn_cause_unreachable"), "computer never arrived");
        require("retry".equals(missed.primaryAction) && missed.secondary.isEmpty(), "one retry");
        require(missed.commandLine.equals("scripts\\run.ps1 pair"), "help keeps the command");
        require(missed.commandWithAddress.equals("scripts\\run.ps1 pair --address http://10.69.1.253:8767"), "address stays whole");
        require(!missed.sentence.contains("http://"), "cause does not embed an address");
        require(missed.how.equals("conn_advanced") && !missed.showQr, "help collapsed");
        require(missed.bluetoothReason.isEmpty(), "failure hides bluetooth");

        FirstConnection.Screen help = FirstConnection.present(facts(false, true, false, true, false, true,
                "", "", "", "http://10.69.1.253:8767", FirstConnection.Link.WIFI, true, FirstConnection.GIVE_UP_MS), COPY);
        require(help.showQr && help.address.equals("http://10.69.1.253:8767"), "help can show the code");

        boolean[][] qrCode = QrCode.encode("http://10.69.1.253:8767");
        require(qrCode.length == 25 && qrCode[0].length == 25, "version 2 size");
        require(qrCode[0][0] && qrCode[0][6] && !qrCode[0][7], "finder");
        require(rows(qrCode).equals(SAMPLE), "address qr matches the known mask");
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

    private static FirstConnection.Facts facts(boolean paired, boolean waiting, boolean qr, boolean attempted,
                                               boolean sawCode, boolean help, String code, String name, String sync,
                                               String address, FirstConnection.Link link, boolean serverUp, long waitedMs) {
        return new FirstConnection.Facts(paired, waiting, qr, attempted, sawCode, help, code, name, sync,
                address, link, serverUp, waitedMs);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
