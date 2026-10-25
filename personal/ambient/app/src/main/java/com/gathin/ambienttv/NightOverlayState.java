package com.gathin.ambienttv;

import java.util.Locale;

/** Window-identity policy only: no accessibility content or account state is inspected. */
final class NightOverlayState {
    private final String ownPackage, playerPackage;
    private boolean playerSeen, playerCurrent, departed;

    NightOverlayState(String ownPackage, String playerPackage) {
        this.ownPackage = ownPackage;
        this.playerPackage = playerPackage;
    }

    /** Return true when an observed departure must end this session. */
    boolean window(CharSequence packageName, CharSequence className) {
        if (departed) return true;
        if (packageName == null || packageName.length() == 0) return false;
        String name = packageName.toString();
        if (playerPackage.equals(name)) {
            playerSeen = playerCurrent = true;
            return false;
        }
        if (transientWindow(name, className)) return false;
        if (playerSeen) {
            playerCurrent = false;
            departed = true;
            return true;
        }
        return false;
    }

    boolean playerSeen() { return playerSeen; }
    boolean canPausePlayer() { return playerCurrent && !departed; }

    private boolean transientWindow(String packageName, CharSequence className) {
        String name = packageName.toLowerCase(Locale.ROOT);
        // Reopening an actual photo/settings activity ends the overlay; its own view windows do not.
        boolean ownOverlay = ownPackage.equals(packageName)
                && (className == null || !className.toString().endsWith("Activity"));
        return ownOverlay || "android".equals(packageName)
                || "com.android.systemui".equals(packageName)
                || name.contains(".inputmethod") || name.endsWith(".ime")
                || "com.android.tv.keyboard".equals(packageName);
    }
}
