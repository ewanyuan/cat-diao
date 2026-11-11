package com.ewan.wallpaperbridge;

/** First-connection steps. Display copy comes from resources through {@link Copy}. */
public final class FirstConnection {
    public static final String SERVICE_TYPE = "_catdiao._tcp";
    public static final int PHONE_PORT = 8767;
    public static final String PC_COMMAND = "run.ps1 pair";
    /** How long a healthy wait runs before we say the computer never arrived. */
    public static final long GIVE_UP_MS = 180_000L;
    /** The listener socket may still be binding in the first moments of the screen. */
    public static final long SERVER_GRACE_MS = 5_000L;

    public interface Copy {
        String t(String key);
        String f(String key, Object... args);
    }

    public enum State { GUIDE, WAITING, PAIRING, PAIRED, PROBLEMS }

    /** What this phone can actually see about its own network. */
    public enum Link { WIFI, CELLULAR, OFFLINE, VPN, CAPTIVE, NO_LAN }

    /** One probed reason. NONE means keep guiding or waiting. */
    public enum Cause {
        NONE, OFFLINE, CELLULAR, CAPTIVE, NO_LAN, VPN, SERVER, UNREACHABLE, TIMED_OUT
    }

    public static final class Facts {
        public final boolean paired;
        public final boolean waiting;
        public final boolean qrOpen;
        public final boolean attempted;
        public final boolean sawCode;
        public final boolean helpOpen;
        public final String confirmCode;
        public final String computerName;
        public final String lastSync;
        public final String address;
        public final Link link;
        public final boolean serverUp;
        public final long waitedMs;

        public Facts(boolean paired, boolean waiting, boolean qrOpen, boolean attempted, boolean sawCode,
                     boolean helpOpen, String confirmCode, String computerName, String lastSync,
                     String address, Link link, boolean serverUp, long waitedMs) {
            this.paired = paired;
            this.waiting = waiting;
            this.qrOpen = qrOpen;
            this.attempted = attempted;
            this.sawCode = sawCode;
            this.helpOpen = helpOpen;
            this.confirmCode = confirmCode == null ? "" : confirmCode;
            this.computerName = computerName == null ? "" : computerName;
            this.lastSync = lastSync == null ? "" : lastSync;
            this.address = address == null ? "" : address;
            this.link = link == null ? Link.WIFI : link;
            this.serverUp = serverUp;
            this.waitedMs = Math.max(0L, waitedMs);
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
        /** Sentence on the card. People read this; it has no Python note. */
        public final String cardText;
        /** Clipboard line for the computer's AI. Includes the Python note the card hides. */
        public final String copyText;
        public final String address;
        public final boolean showAddress;
        public final boolean showQr;
        public final String how;
        public final String code;
        public final String[] causes;
        public final boolean green;
        public final String detail;
        public final String commandLine;
        public final String commandWithAddress;

        Screen(State state, String title, String sentence, String primary, String primaryAction,
               String secondary, String secondaryAction, boolean bluetoothEnabled, String bluetoothReason,
               String cardText, String copyText, String address, boolean showAddress, boolean showQr, String how,
               String code, String[] causes, boolean green, String detail,
               String commandLine, String commandWithAddress) {
            this.state = state;
            this.title = title;
            this.sentence = sentence;
            this.primary = primary;
            this.primaryAction = primaryAction;
            this.secondary = secondary;
            this.secondaryAction = secondaryAction;
            this.bluetoothEnabled = bluetoothEnabled;
            this.bluetoothReason = bluetoothReason;
            this.cardText = cardText == null ? "" : cardText;
            this.copyText = copyText;
            this.address = address;
            this.showAddress = showAddress;
            this.showQr = showQr;
            this.how = how;
            this.code = code;
            this.causes = causes;
            this.green = green;
            this.detail = detail;
            this.commandLine = commandLine;
            this.commandWithAddress = commandWithAddress;
        }
    }

    private FirstConnection() {}

    /** PowerShell path with a real backslash. Kept out of XML so the slash cannot be eaten. */
    public static String commandLine() {
        return "scripts\\" + PC_COMMAND;
    }

    public static String commandLine(String address) {
        if (address != null && address.startsWith("http://"))
            return commandLine() + " --address " + address;
        return "";
    }

    public static Link classify(boolean lanTransport, boolean cellular, boolean vpn,
                                 boolean captive, boolean hasLanAddress) {
        if (vpn) return Link.VPN;
        if (captive) return Link.CAPTIVE;
        if (lanTransport && hasLanAddress) return Link.WIFI;
        if (lanTransport) return Link.NO_LAN;
        if (cellular) return Link.CELLULAR;
        return Link.OFFLINE;
    }

    public static Cause diagnose(Facts facts) {
        if (facts.paired || isCode(facts.confirmCode) || !facts.attempted) return Cause.NONE;
        switch (facts.link) {
            case VPN: return Cause.VPN;
            case CAPTIVE: return Cause.CAPTIVE;
            case NO_LAN: return Cause.NO_LAN;
            case CELLULAR: return Cause.CELLULAR;
            case OFFLINE: return Cause.OFFLINE;
            case WIFI:
            default:
                break;
        }
        if (!facts.serverUp && facts.waitedMs >= SERVER_GRACE_MS) return Cause.SERVER;
        if (facts.sawCode) return Cause.TIMED_OUT;
        if (facts.waitedMs >= GIVE_UP_MS) return Cause.UNREACHABLE;
        return Cause.NONE;
    }

    public static String causeKey(Cause cause) {
        switch (cause) {
            case OFFLINE: return "conn_cause_offline";
            case CELLULAR: return "conn_cause_cellular";
            case CAPTIVE: return "conn_cause_captive";
            case NO_LAN: return "conn_cause_nolan";
            case VPN: return "conn_cause_vpn";
            case SERVER: return "conn_cause_server";
            case UNREACHABLE: return "conn_cause_unreachable";
            case TIMED_OUT: return "conn_cause_timeout";
            default: return "";
        }
    }

    /** Sentence on the card. The repo URL stays; the Python note does not. */
    public static String shownLine(Copy copy, String address) {
        if (address != null && address.startsWith("http://"))
            return copy.f("conn_ai_line_with_address", address);
        return copy.t("conn_ai_line");
    }

    /** Clipboard line for the computer's AI. Same sentence, plus the Python note. */
    public static String pasteLine(Copy copy, String address) {
        if (address != null && address.startsWith("http://"))
            return copy.f("conn_ai_paste_with_address", address);
        return copy.t("conn_ai_paste");
    }

    public static Screen present(Facts facts, Copy copy) {
        if (isCode(facts.confirmCode)) return pairing(copy, facts.confirmCode.trim());
        if (facts.paired) return paired(copy, facts);
        Cause cause = diagnose(facts);
        if (cause != Cause.NONE) return problems(copy, facts, cause);
        if (facts.waiting) return waiting(copy, facts);
        return guide(copy, facts);
    }

    private static boolean isCode(String code) {
        String trimmed = code == null ? "" : code.trim();
        return trimmed.length() == 6 && trimmed.chars().allMatch(Character::isDigit);
    }

    private static Screen guide(Copy copy, Facts facts) {
        if (facts.link != Link.WIFI) {
            Cause cause = facts.link == Link.CELLULAR ? Cause.CELLULAR
                    : facts.link == Link.OFFLINE ? Cause.OFFLINE
                    : facts.link == Link.VPN ? Cause.VPN
                    : facts.link == Link.CAPTIVE ? Cause.CAPTIVE
                    : Cause.NO_LAN;
            return screen(State.GUIDE, copy.t("conn_not_paired_title"), copy.t(causeKey(cause)),
                    copy.t("conn_recheck"), "recheck", "", "",
                    "", "", facts.address, false, false, "", "", "", false, "");
        }
        return screen(State.GUIDE, copy.t("conn_not_paired_title"), copy.t("conn_not_paired_body"),
                copy.t("conn_copy"), "copy",
                copy.t(facts.qrOpen ? "conn_hide_qr" : "conn_show_qr"), "qr",
                shownLine(copy, facts.address), pasteLine(copy, facts.address), facts.address,
                false, facts.qrOpen, "", "", "",
                false, copy.t("conn_wifi_ok"));
    }

    private static Screen waiting(Copy copy, Facts facts) {
        int seconds = (int) Math.min(Integer.MAX_VALUE, facts.waitedMs / 1000L);
        return screen(State.WAITING, copy.t("conn_waiting_title"),
                copy.f("conn_waiting_body", seconds),
                "", "",
                copy.t("conn_copy"), "copy",
                shownLine(copy, facts.address), pasteLine(copy, facts.address), facts.address,
                false, false, "", "", "",
                false, copy.t("conn_code_pending"));
    }

    private static Screen pairing(Copy copy, String code) {
        return screen(State.PAIRING, copy.t("conn_waiting_title"), copy.t("conn_pairing_body"),
                copy.t("conn_allow"), "allow", copy.t("conn_deny"), "deny",
                "", "", "", false, false, "", code, "", false, "");
    }

    private static Screen paired(Copy copy, Facts facts) {
        String name = facts.computerName.isEmpty() ? copy.t("conn_this_computer") : facts.computerName;
        String detail = facts.lastSync.isEmpty()
                ? copy.t("conn_sync_empty") : copy.f("conn_sync", facts.lastSync);
        return screen(State.PAIRED, copy.t("conn_paired_title"),
                copy.f("conn_paired_body", name),
                copy.t("conn_try"), "try", copy.t("conn_done"), "done",
                "", "", "", false, false, "", "", "", true, detail);
    }

    private static Screen problems(Copy copy, Facts facts, Cause cause) {
        boolean http = facts.address.startsWith("http://");
        return new Screen(State.PROBLEMS, copy.t("conn_problems_title"), copy.t(causeKey(cause)),
                copy.t("conn_retry"), "retry", "", "",
                false, "", "", "", facts.address, http, facts.helpOpen,
                copy.t("conn_advanced"), "", new String[0], false, "",
                commandLine(), commandLine(facts.address));
    }

    private static Screen screen(State state, String title, String sentence, String primary, String primaryAction,
                                 String secondary, String secondaryAction, String cardText, String copyText,
                                 String address, boolean showAddress, boolean showQr, String how, String code,
                                 String detailKey, boolean green, String detail) {
        return new Screen(state, title, sentence, primary, primaryAction, secondary, secondaryAction,
                false, "", cardText, copyText, address, showAddress, showQr, how, code, new String[0], green, detail,
                "", "");
    }
}
