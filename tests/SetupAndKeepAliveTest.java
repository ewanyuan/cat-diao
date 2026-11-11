package com.ewan.wallpaperbridge;

/** Pairing wake window and the optional overlay step. No device required. */
public final class SetupAndKeepAliveTest {
    public static void main(String[] args) {
        require(PairingKeepAlive.holdScreen(FirstConnection.State.WAITING), "waiting holds the screen");
        require(PairingKeepAlive.holdScreen(FirstConnection.State.PAIRING), "confirm holds the screen");
        require(!PairingKeepAlive.holdScreen(FirstConnection.State.GUIDE), "guide does not pin the screen");
        require(!PairingKeepAlive.holdScreen(FirstConnection.State.PROBLEMS), "failure does not pin the screen");
        require(!PairingKeepAlive.holdScreen(FirstConnection.State.PAIRED), "success is a short grace, not a hold");
        require(PairingKeepAlive.pairedGrace(FirstConnection.State.PAIRED), "grace after allow");
        require(!PairingKeepAlive.pairedGrace(FirstConnection.State.PAIRING), "confirm is the long hold");
        require(PairingKeepAlive.HOLD_MS >= 120_000L, "cover the phone approval window");
        require(PairingKeepAlive.PAIRED_GRACE_MS >= 30_000L, "computer can still observe approval");
        require(PairingKeepAlive.wakeActive(1_000L, 999L), "deadline still ahead");
        require(!PairingKeepAlive.wakeActive(1_000L, 1_000L), "deadline reached");
        require(!PairingKeepAlive.wakeActive(0L, 5L), "no deadline");
        require(PairingKeepAlive.restartListener(true, false, false), "saved token restarts");
        require(PairingKeepAlive.restartListener(false, true, false), "screen hold restarts before a token");
        require(PairingKeepAlive.restartListener(false, false, true), "pending confirm restarts");
        require(!PairingKeepAlive.restartListener(false, false, false), "idle unpaired does not loop");

        require(SetupFlow.next(false, false, false, false) == SetupFlow.SCREEN, "screen first");
        require(SetupFlow.next(true, false, false, false) == SetupFlow.OVERLAY, "offer overlay once");
        require(SetupFlow.next(true, false, true, false) == SetupFlow.WRITE, "skip still reaches write settings");
        require(SetupFlow.next(true, true, false, false) == SetupFlow.WRITE, "granted overlay is not asked again");
        require(SetupFlow.next(true, false, true, true) == SetupFlow.DONE, "skip can finish");
        require(SetupFlow.next(true, false, false, true) == SetupFlow.OVERLAY, "write alone does not hide the offer");
        require(SetupFlow.next(false, true, true, true) == SetupFlow.SCREEN, "screen stays required");
        require(SetupFlow.finished(true, true), "required steps are screen and write");
        require(!SetupFlow.finished(true, false), "write still required");
        require(!SetupFlow.finished(false, true), "screen still required");
        require(SetupFlow.readyCount(true, false) == 1, "overlay is not a required count");
        require(SetupFlow.readyCount(true, true) == SetupFlow.REQUIRED, "two required steps");
        require(SetupFlow.canSkip(SetupFlow.OVERLAY), "overlay can be skipped");
        require(!SetupFlow.canSkip(SetupFlow.SCREEN), "screen is not skippable");
        require(!SetupFlow.canSkip(SetupFlow.WRITE), "write settings are not skippable");
        System.out.println("PASS pairing keep-alive and optional overlay");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
