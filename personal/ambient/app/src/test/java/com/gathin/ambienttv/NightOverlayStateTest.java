package com.gathin.ambienttv;

import org.junit.Test;

import static org.junit.Assert.*;

public class NightOverlayStateTest {
    private static final String OWN = "com.gathin.ambienttv";
    private static final String PLAYER = "io.gh.reisxd.tizentube.cobalt";

    @Test public void startupCannotPauseAnUnobservedPlayer() {
        NightOverlayState state = new NightOverlayState(OWN, PLAYER);
        assertFalse(state.playerSeen());
        assertFalse(state.canPausePlayer());
        assertFalse(state.window(OWN, OWN + ".NightAccessActivity"));
        assertFalse(state.window("com.gathin.personalhome", "com.gathin.tvhome.MainActivity"));
        assertFalse(state.canPausePlayer());
        assertFalse(state.window(PLAYER, "dev.cobalt.app.MainActivity"));
        assertTrue(state.playerSeen());
        assertTrue(state.canPausePlayer());
    }

    @Test public void homeDepartureRemovesOverlayWithoutClaimingAnotherPlayer() {
        NightOverlayState state = playing();
        assertTrue(state.window("com.gathin.personalhome", "com.gathin.tvhome.MainActivity"));
        assertFalse(state.canPausePlayer());
    }

    @Test public void ownOverlayAndSystemVolumeWindowsKeepVideoSession() {
        NightOverlayState state = playing();
        assertFalse(state.window(OWN, "android.widget.FrameLayout"));
        assertFalse(state.window("com.android.systemui", "android.app.Dialog"));
        assertFalse(state.window("android", "android.widget.PopupWindow"));
        assertTrue(state.canPausePlayer());
    }

    @Test public void ownPhotoActivityIsARealDeparture() {
        NightOverlayState state = playing();
        assertTrue(state.window(OWN, OWN + ".SlideshowActivity"));
        assertFalse(state.canPausePlayer());
    }

    @Test public void keyboardDoesNotEndThePlayerSession() {
        NightOverlayState state = playing();
        assertFalse(state.window("com.google.android.inputmethod.latin", "android.inputmethodservice.SoftInputWindow"));
        assertTrue(state.canPausePlayer());
    }

    @Test public void stalePlayerEventCannotReviveDepartedSession() {
        NightOverlayState state = playing();
        assertTrue(state.window("com.netflix.ninja", "com.netflix.ninja.MainActivity"));
        assertTrue(state.window(PLAYER, "dev.cobalt.app.MainActivity"));
        assertFalse(state.canPausePlayer());
    }

    @Test public void missingIdentityAndPlaylistReentryDoNotResetObservation() {
        NightOverlayState state = playing();
        assertFalse(state.window(null, null));
        assertFalse(state.window(PLAYER, "dev.cobalt.app.MainActivity"));
        assertTrue(state.playerSeen());
        assertTrue(state.canPausePlayer());
    }

    private NightOverlayState playing() {
        NightOverlayState state = new NightOverlayState(OWN, PLAYER);
        state.window(PLAYER, "dev.cobalt.app.MainActivity");
        return state;
    }
}
