package com.ewan.wallpaperbridge;

/** First-connection steps. Display copy comes from resources through {@link Copy}. */
public final class FirstConnection {
    public static final String SERVICE_TYPE = "_catdiao._tcp";
    public static final int PHONE_PORT = 8767;
    public static final String PC_COMMAND = "run.ps1 pair";

    public interface Copy {
        String t(String key);
        String f(String key, Object... args);
    }

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
    public static String aiLine(Copy copy, String address) {
        if (address != null && address.startsWith("http://"))
            return copy.f("conn_ai_line_with_address", address);
        return copy.t("conn_ai_line");
    }

    public static Screen present(Facts facts, Copy copy) {
        String code = facts.confirmCode.trim();
        if (code.length() == 6 && code.chars().allMatch(Character::isDigit)) return pairing(copy, code);
        if (facts.paired) return paired(copy, facts);
        if (facts.askedForHelp) return problems(copy, facts.address);
        if (facts.askedToWait) return waiting(copy, facts.address);
        return notPaired(copy);
    }

    private static Screen notPaired(Copy copy) {
        return new Screen(State.NOT_PAIRED, copy.t("conn_not_paired_title"),
                copy.t("conn_not_paired_body"),
                copy.t("conn_start"), "start", "", "",
                false, copy.t("conn_bt_locked"), "", "", false, false, "", "", new String[0], false, "");
    }

    private static Screen waiting(Copy copy, String address) {
        return new Screen(State.WAITING, copy.t("conn_waiting_title"),
                copy.t("conn_waiting_body"),
                copy.t("conn_copy"), "copy", copy.t("conn_still_stuck"), "problems",
                false, copy.t("conn_bt_locked"), aiLine(copy, address), address, false, true,
                advanced(copy, address), "", new String[0], false, "");
    }

    private static Screen pairing(Copy copy, String code) {
        return new Screen(State.PAIRING, copy.t("conn_pairing_title"),
                copy.t("conn_pairing_body"),
                copy.t("conn_allow"), "allow", copy.t("conn_deny"), "deny",
                false, "", "", "", false, false, "", code, new String[0], false, "");
    }

    private static Screen paired(Copy copy, Facts facts) {
        String name = facts.computerName.isEmpty() ? copy.t("conn_this_computer") : facts.computerName;
        String detail = facts.lastSync.isEmpty()
                ? copy.t("conn_sync_empty") : copy.f("conn_sync", facts.lastSync);
        return new Screen(State.PAIRED, copy.t("conn_paired_title"),
                copy.f("conn_paired_body", name),
                copy.t("conn_done"), "done", copy.t("conn_bluetooth_setup"), "bluetooth",
                true, "", "", "", false, false, "", "", new String[0], true, detail);
    }

    private static Screen problems(Copy copy, String address) {
        String[] causes = {
                copy.t("conn_cause_wifi"), copy.t("conn_cause_hotspot"), copy.t("conn_cause_advanced")
        };
        return new Screen(State.PROBLEMS, copy.t("conn_problems_title"),
                copy.t("conn_problems_body"),
                copy.t("conn_retry"), "retry", "", "",
                false, copy.t("conn_bt_locked"), aiLine(copy, address), address, false, true,
                advanced(copy, address), "", causes, false, "");
    }

    /** Manual PowerShell / address — collapsed under Advanced, not the main path. */
    public static String advanced(Copy copy, String address) {
        String addr = (address != null && address.startsWith("http://"))
                ? address : copy.t("conn_addr_placeholder");
        return copy.f("conn_advanced", PC_COMMAND, addr);
    }
}
