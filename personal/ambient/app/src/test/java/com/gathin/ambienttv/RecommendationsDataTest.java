package com.gathin.ambienttv;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class RecommendationsDataTest {
    private static final String OWNER = "com.example.player";
    private RecommendationsData.Row row(String intent, boolean watchNext, int type,
            long position, long duration, boolean live) {
        return RecommendationsData.row(OWNER, OWNER, "Published episode", "Shared description",
                intent, "https://example.org/poster.jpg", watchNext, type, position, duration, live);
    }

    @Test public void ownersAreBoundedAndExact() {
        assertTrue(RecommendationsData.validOwner(OWNER));
        assertFalse(RecommendationsData.validOwner("com.example/player"));
        assertFalse(RecommendationsData.validOwner("a".repeat(161)));
        assertNull(RecommendationsData.row("com.other.player", OWNER, "Episode", "",
                "player://episode/1", "", true, 0, 1, 2, false));
    }

    @Test public void missingOrMalformedRequiredColumnsAreSkipped() {
        assertNull(RecommendationsData.row(OWNER, OWNER, "  ", "", "player://1", "", false, 0, 0, 0, false));
        assertNull(row("", false, 0, 0, 0, false));
        assertNull(row("not a uri", false, 0, 0, 0, false));
        assertNull(row("player://episode\n1", false, 0, 0, 0, false));
        assertNotNull(row("intent://episode/1#Intent;scheme=player;package=com.example.player;end", true, 0, 0, 0, false));
    }

    @Test public void publishedWatchNextTypeDeterminesKind() {
        assertEquals("continue", row("player://1", true, 0, 12, 100, false).kind);
        assertEquals("next", row("player://2", true, 1, 0, 100, false).kind);
        assertEquals("preview", row("player://3", true, 2, 0, 100, false).kind);
        assertEquals("preview", row("player://4", false, 0, 0, 100, true).kind);
        assertTrue(row("player://4", false, 0, 0, 100, true).live);
    }

    @Test public void absentProgressStaysZeroAndInvalidValuesAreBounded() {
        RecommendationsData.Row absent = row("player://1", true, 0, -1, -1, false);
        assertEquals(0, absent.position);
        assertEquals(0, absent.duration);
        assertEquals(100, row("player://1", true, 0, 200, 100, false).position);
    }

    @Test public void watchNextPrecedesDuplicatePreviewWithoutInventingRows() {
        RecommendationsData.Row watch = row("player://1", true, 0, 20, 100, false);
        RecommendationsData.Row preview = row("player://1", false, 0, 0, 100, false);
        List<RecommendationsData.Row> rows = RecommendationsData.distinct(Arrays.asList(watch, null, preview), 18);
        assertEquals(1, rows.size());
        assertSame(watch, rows.get(0));
        assertTrue(RecommendationsData.distinct(new ArrayList<>(), 18).isEmpty());
    }

    @Test public void resultAndArtworkIdentityAreBoundedAndOwnerSpecific() {
        List<RecommendationsData.Row> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) rows.add(row("player://" + i, false, 0, 0, 0, false));
        assertEquals(18, RecommendationsData.distinct(rows, 100).size());
        assertTrue(RecommendationsData.distinct(rows, 0).isEmpty());
        String first = RecommendationsData.artworkKey(OWNER, "https://example.org/poster.jpg");
        assertTrue(first.matches("[a-f0-9]{64}"));
        assertEquals(first, RecommendationsData.artworkKey(OWNER, "https://example.org/poster.jpg"));
        assertNotEquals(first, RecommendationsData.artworkKey("com.other", "https://example.org/poster.jpg"));
    }
}
