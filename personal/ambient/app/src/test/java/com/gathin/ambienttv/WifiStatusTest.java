package com.gathin.ambienttv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class WifiStatusTest {
    @Test public void redactedAndMissingNamesUseConnectedFallback() {
        for (String name : new String[] { null, "", "\"\"", "<unknown ssid>", "\"<unknown ssid>\"",
                "\"  <unknown ssid>  \"", "\"<unknown ssid>\u202e\"", "0x" }) {
            assertEquals("Wi-Fi connecté", WifiStatus.ssidLabel(name));
        }
    }

    @Test public void quotedNamesKeepUnicodeAndInternalSpaces() {
        assertEquals("Réseau invité", WifiStatus.ssidLabel("\"Réseau invité\""));
    }

    @Test public void networkNamesCannotInsertLinesOrChangeTextDirection() {
        assertEquals("Example network", WifiStatus.ssidLabel("\"Example\n network\u202e\""));
        assertEquals("Wi-Fi connecté", WifiStatus.ssidLabel("\"\n\u202e\""));
    }

    @Test public void longNamesAreRetainedForTheViewToEllipsize() {
        String name = "Example Wi-Fi network with a long name";
        assertEquals(name, WifiStatus.ssidLabel("\"" + name + "\""));
    }
}
