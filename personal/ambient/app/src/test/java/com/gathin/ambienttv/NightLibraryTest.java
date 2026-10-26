package com.gathin.ambienttv;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class NightLibraryTest {
    @Test public void dynamicPlaylistRoundTripsNewIdsAndArabicTitles() throws Exception {
        List<NightLibrary.Entry> entries = new java.util.ArrayList<>(NightLibrary.parse("", null));
        entries.add(new NightLibrary.Entry("abcdEFG_123", "أجواء هادئة", null, ""));
        List<NightLibrary.Entry> saved = NightLibrary.parse(NightLibrary.playlistJson(entries), null);
        assertEquals(5, saved.size());
        assertEquals("abcdEFG_123", saved.get(4).id);
        assertEquals("أجواء هادئة", saved.get(4).title);
        assertNull(saved.get(4).file);
    }

    @Test public void removingBuiltInVideosSurvivesReload() throws Exception {
        List<NightLibrary.Entry> entries = new java.util.ArrayList<>(NightLibrary.parse("", null));
        entries.remove(0);
        List<NightLibrary.Entry> saved = NightLibrary.parse(NightLibrary.playlistJson(entries), null);
        assertEquals(3, saved.size());
        assertEquals("BVdHrTfO2PE", saved.get(0).id);
    }

    @Test public void invalidDynamicFileNeverPartiallyReplacesPlaylist() {
        String[] invalid = {"{\"version\":2,\"videos\":[]}",
                "{\"version\":2,\"videos\":[{\"id\":\"../escape\",\"title\":\"Bad\"}]}",
                "{\"version\":2,\"videos\":[{\"id\":\"7_Wbmy79wxQ\",\"title\":\"A\"},{\"id\":\"7_Wbmy79wxQ\",\"title\":\"B\"}]}"};
        for (String raw : invalid) assertEquals(4, NightLibrary.parse(raw, null).size());
    }

    @Test public void boundedLargePlaylistKeepsOrder() throws Exception {
        List<NightLibrary.Entry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) entries.add(new NightLibrary.Entry(String.format(java.util.Locale.ROOT, "%011d", i), "Video " + i, null, ""));
        List<NightLibrary.Entry> saved = NightLibrary.parse(NightLibrary.playlistJson(entries), null);
        assertEquals(50, saved.size());
        assertEquals("Video 49", saved.get(49).title);
        entries.add(new NightLibrary.Entry("abcdEFG_123", "Extra", null, ""));
        try { NightLibrary.playlistJson(entries); fail("Oversized playlist accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void titlesCannotInjectLineBreaksOrControlCharacters() {
        assertTrue(NightLibrary.validTitle("Titre عربي"));
        assertFalse(NightLibrary.validTitle("line\nbreak"));
        assertFalse(NightLibrary.validTitle("\u001b[31m"));
        assertFalse(NightLibrary.validId("https://youtu.be/7_Wbmy79wxQ"));
    }
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
