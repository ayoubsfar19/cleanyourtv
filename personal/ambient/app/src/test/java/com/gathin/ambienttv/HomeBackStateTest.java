package com.gathin.ambienttv;
import org.junit.Test;
import static org.junit.Assert.*;

public class HomeBackStateTest {
    @Test public void opensOnlyAfterCompleteHomePress() {
        HomeBackState s=new HomeBackState();
        assertFalse(s.begin());
        s.window("com.gathin.tvhome","com.gathin.tvhome.MainActivity");
        assertTrue(s.begin());assertTrue(s.finish(false));assertFalse(s.finish(false));
    }
    @Test public void appChangeBetweenDownAndUpCancelsLaunch() {
        HomeBackState s=new HomeBackState();s.window("com.gathin.tvhome","com.gathin.tvhome.MainActivity");
        assertTrue(s.begin());s.window("com.google.android.youtube.tv","Player");
        assertTrue(s.captured());assertFalse(s.finish(false));assertFalse(s.begin());
    }
    @Test public void dialogAndUnknownWindowsNeverCapture() {
        HomeBackState s=new HomeBackState();s.window("com.gathin.tvhome","android.app.Dialog");assertFalse(s.begin());
        s.window(null,null);assertFalse(s.begin());
    }
    @Test public void canceledKeyAndInterruptionNeverLaunch() {
        HomeBackState s=new HomeBackState();s.window("com.gathin.tvhome","com.gathin.tvhome.MainActivity");
        assertTrue(s.begin());assertFalse(s.finish(true));assertTrue(s.begin());s.reset();
        assertFalse(s.finish(false));assertFalse(s.begin());
    }
}
