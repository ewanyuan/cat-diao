# Floating cat and quick panel

The floating cat is a small overlay. It is not a full-screen window, and it does not sit past the screen edge. Pairing, saves, and screen control do not use it. The post-pair setup step can be skipped, and setup still finishes. Turn the cat on later from More.

## What each close does

| Action | Result |
| --- | --- |
| Tap the cat | Opens the quick panel. The cat hides while any Cat Diao screen is open, then comes back. |
| **Close** on the panel, Back, or a tap outside the panel | Hides the panel only. The cat stays on. |
| **✕** on the cat, long-press, drag onto “drop here”, **Turn off cat** in the panel, the notification action, or **More → Turn off floating cat** | Turns the cat off. It stays off across leaving the app, opening it again, and a service restart. |
| Idle for about 2.5 seconds | The cat picture fades. The ✕ stays visible. This is not off. |

Turning the cat off writes `overlay_wanted=false` with `commit()` and removes every overlay window, including the drag target. It does **not** stop the bridge service, the computer connection, or screen-control accessibility.

Turn it on again from **More**. If overlay permission is missing, no window is added.

The overlay window is only the cat plus the ✕ (about 90dp wide). Touches outside it pass through. `FLAG_LAYOUT_NO_LIMITS` is not used, so the window is not stretched into an invisible full-screen touch layer.
