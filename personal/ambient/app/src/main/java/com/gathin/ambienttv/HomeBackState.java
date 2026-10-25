package com.gathin.ambienttv;

/** The Back shortcut is restricted to a complete key press on the launcher main window. */
final class HomeBackState {
    private boolean home;
    private boolean captured;

    void window(CharSequence packageName, CharSequence className) {
        home = "com.gathin.tvhome".contentEquals(packageName == null ? "" : packageName)
                && "com.gathin.tvhome.MainActivity".contentEquals(className == null ? "" : className);
    }

    boolean begin() {
        captured = home;
        return captured;
    }

    boolean captured() { return captured; }

    boolean finish(boolean canceled) {
        boolean launch = captured && home && !canceled;
        captured = false;
        return launch;
    }

    void reset() { home = false; captured = false; }
}
