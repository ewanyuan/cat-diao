package com.ewan.wallpaperbridge;

/** First-connection copy. One sentence and one primary action for each state. */
public final class FirstConnection {
    public static final String SERVICE_TYPE = "_catdiao._tcp";
    public static final int PHONE_PORT = 8767;
    public static final String PC_COMMAND = "run.ps1 pair";
    /** Short line a person pastes to an AI tool. Address is appended when known. */
    public static final String AI_LINE = "用猫叼连上我这台手机";
    public static final String QR_HINT = "截图这个二维码发给 AI";
    public static final String BLUETOOTH_LOCKED = "先连上电脑，再设置蓝牙备用。";
    public static final String[] CAUSES = {
            "手机和电脑需要连在同一个可互访的 Wi-Fi。",
            "公司或公共 Wi-Fi 可能隔离设备。可以让手机开热点，电脑连上热点，或换家里 Wi-Fi。",
            "还是不行时，把本页短话或二维码截图发给电脑上的 AI；或打开「高级」自己连接。"
    };

    public enum State { NOT_PAIRED, WAITING, PAIRING, PAIRED, PROBLEMS }

    public static final class Facts {
        public final boolean paired;
        public final boolean askedToWait;
        public final boolean askedForHelp;
        public final String confirmCode;
        public final String computerName;
        public final String lastSync;
        public final String address;

        public Facts(boolean paired, boolean askedToWait, boolean askedForHelp, String confirmCode,
                     String computerName, String lastSync, String address) {
            this.paired = paired;
            this.askedToWait = askedToWait;
            this.askedForHelp = askedForHelp;
            this.confirmCode = confirmCode == null ? "" : confirmCode;
            this.computerName = computerName == null ? "" : computerName;
            this.lastSync = lastSync == null ? "" : lastSync;
            this.address = address == null ? "" : address;
        }
    }

    public static final class Screen {
        public final State state;
        public final String title;
        public final String sentence;
        public final String primary;
        public final String primaryAction;
        public final String secondary;
        public final String secondaryAction;
        public final boolean bluetoothEnabled;
        public final String bluetoothReason;
        public final String copyText;
        public final String address;
        public final boolean showAddress;
        public final boolean showQr;
        public final String how;
        public final String code;
        public final String[] causes;
        public final boolean green;
        public final String detail;

        Screen(State state, String title, String sentence, String primary, String primaryAction,
               String secondary, String secondaryAction, boolean bluetoothEnabled, String bluetoothReason,
               String copyText, String address, boolean showAddress, boolean showQr, String how,
               String code, String[] causes, boolean green, String detail) {
            this.state = state;
            this.title = title;
            this.sentence = sentence;
            this.primary = primary;
            this.primaryAction = primaryAction;
            this.secondary = secondary;
            this.secondaryAction = secondaryAction;
            this.bluetoothEnabled = bluetoothEnabled;
            this.bluetoothReason = bluetoothReason;
            this.copyText = copyText;
            this.address = address;
            this.showAddress = showAddress;
            this.showQr = showQr;
            this.how = how;
            this.code = code;
            this.causes = causes;
            this.green = green;
            this.detail = detail;
        }
    }

    private FirstConnection() {}

    /** One short sentence an AI can act on. Embeds the phone URL when known. */
    public static String aiLine(String address) {
        if (address != null && address.startsWith("http://")) return AI_LINE + "：" + address;
        return AI_LINE;
    }

    public static Screen present(Facts facts) {
        String code = facts.confirmCode.trim();
        if (code.length() == 6 && code.chars().allMatch(Character::isDigit)) return pairing(code);
        if (facts.paired) return paired(facts);
        if (facts.askedForHelp) return problems(facts.address);
        if (facts.askedToWait) return waiting(facts.address);
        return notPaired();
    }

    private static Screen notPaired() {
        return new Screen(State.NOT_PAIRED, "连接电脑",
                "还没连上电脑。收藏会先留在这台手机里。",
                "开始连接", "start", "", "",
                false, BLUETOOTH_LOCKED, "", "", false, false, "", "", new String[0], false, "");
    }

    private static Screen waiting(String address) {
        return new Screen(State.WAITING, "等电脑",
                "任选一种发给电脑上的 AI，人不用管。",
                "复制这句话", "copy", "还是连不上", "problems",
                false, BLUETOOTH_LOCKED, aiLine(address), address, false, true,
                advanced(address), "", new String[0], false, "");
    }

    private static Screen pairing(String code) {
        return new Screen(State.PAIRING, "核对数字",
                "和电脑上的一样，再点允许。不同就点拒绝。",
                "允许", "allow", "拒绝", "deny",
                false, "", "", "", false, false, "", code, new String[0], false, "");
    }

    private static Screen paired(Facts facts) {
        String name = facts.computerName.isEmpty() ? "这台电脑" : facts.computerName;
        String detail = facts.lastSync.isEmpty() ? "最近一次同步：还没有记录" : "最近一次同步：" + facts.lastSync;
        return new Screen(State.PAIRED, "已连上", "已经连上「" + name + "」。",
                "完成", "done", "设置蓝牙备用连接", "bluetooth",
                true, "", "", "", false, false, "", "", new String[0], true, detail);
    }

    private static Screen problems(String address) {
        return new Screen(State.PROBLEMS, "还没连上", "先看是不是下面一种情况。",
                "再试一次", "retry", "", "",
                false, BLUETOOTH_LOCKED, aiLine(address), address, false, true,
                advanced(address), "", CAUSES, false, "");
    }

    /** Manual PowerShell / address — collapsed under 「高级」, not the main path. */
    public static String advanced(String address) {
        String addr = (address != null && address.startsWith("http://")) ? address : "http://手机IP:8767";
        return "自己在电脑上操作时：\n"
                + "运行 scripts\\" + PC_COMMAND + "\n"
                + "找不到时运行 scripts\\" + PC_COMMAND + " --address " + addr + "\n"
                + "手机上会出现 6 位数字。和电脑上的一样，再点允许。";
    }

    /** @deprecated kept for older call sites; use {@link #advanced(String)}. */
    @Deprecated
    public static String how() {
        return advanced("http://IP:8767");
    }
}
