package com.gathin.ambienttv;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class NightLibraryTest {
    @Test public void missingConfigurationKeepsFourSeparateLocalSlots() {
        List<NightLibrary.Entry> entries = NightLibrary.parse("", new File("/private"));
        assertEquals(4, entries.size());
        assertEquals("BVdHrTfO2PE", entries.get(1).id);
        assertEquals(new File("/private/night-videos/2.mp4"), entries.get(1).file);
        assertEquals("", entries.get(1).url);
    }

    @Test public void userConfigCannotEscapeVideoDirectoryOrReorderSlots() {
        String json = "{\"version\":1,\"videos\":[{\"id\":\"BUeCNBg_qYk\",\"file\":\"../../pictures/photo.jpg\",\"title\":\"Soirée\",\"url\":\"https://media.example/video.mp4\"}]}";
        List<NightLibrary.Entry> entries = NightLibrary.parse(json, new File("/private"));
        assertEquals("7_Wbmy79wxQ", entries.get(0).id);
        assertEquals("Soirée", entries.get(3).title);
        assertEquals(new File("/private/night-videos/4.mp4"), entries.get(3).file);
        assertEquals("https://media.example/video.mp4", entries.get(3).url);
    }

    @Test public void malformedOrOversizedConfigurationCannotBreakDefaultSlots() {
        for (String raw : new String[]{"broken", "{\"version\":2}", "x".repeat(65_537)}) {
            assertEquals(4, NightLibrary.parse(raw, null).size());
            assertNull(NightLibrary.parse(raw, null).get(0).file);
        }
    }

    @Test public void onlyNativeHttpsSourcesAreAccepted() {
        for (String bad : new String[]{"http://media.example/a.mp4", "file:///private/a.mp4", "javascript:alert(1)",
                "https://www.youtube.com/watch?v=7_Wbmy79wxQ", "https://youtu.be/7_Wbmy79wxQ",
                "https://www.youtube-nocookie.com/embed/id", "https://r1.googlevideo.com/videoplayback",
                "https://user:password@example.com/a.mp4"}) {
            assertEquals(bad, "", NightLibrary.directUrl(bad));
        }
        assertEquals("https://cdn.example/a.mp4?token=abc", NightLibrary.directUrl("https://cdn.example/a.mp4?token=abc"));
    }

    @Test public void duplicateIdsCannotOverrideTheFirstConfiguredSource() {
        String json = "{\"version\":1,\"videos\":[{\"id\":\"7_Wbmy79wxQ\",\"title\":\"First\"},{\"id\":\"7_Wbmy79wxQ\",\"title\":\"Second\"},{\"id\":\"unknown\"}]}";
        assertEquals("First", NightLibrary.parse(json, null).get(0).title);
    }

    @Test public void providerRoutesNightWritesWithoutBroadeningPhotoDeletion() {
        assertEquals("night-videos/1.mp4", Media.path(Arrays.asList("night-videos", "1.mp4")));
        assertEquals("night-videos.json", Media.path(Arrays.asList("night-videos.json")));
        assertNull(Media.path(Arrays.asList("night-videos", "5.mp4")));
        assertNull(Media.path(Arrays.asList("night-videos", "..", "pictures", "1.mp4")));
        assertNull(Media.path(Arrays.asList("night-videos", "photo.jpg")));
        assertFalse(Media.isFolder(Arrays.asList("night-videos")));
        assertTrue(Media.isFolder(Arrays.asList("pictures")));
    }
}
