package com.gathin.ambienttv;

import org.junit.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.*;

public class AdhkarContentTest {
    @Test public void onlyTheTwoOwnerSelectedVideoIdentitiesQualify() {
        assertTrue(AdhkarContent.supportsVideo("7_Wbmy79wxQ"));
        assertTrue(AdhkarContent.supportsVideo("BVdHrTfO2PE"));
        for (String other : new String[]{null, "", "yTRHheKoVhY", "BUeCNBg_qYk", "newArtVideo",
                "The Art Corner", "The Art Corner · 1", "The Art Corner · 2", "@TheArtCorner",
                "7_wbmy79wxq", "bvDhrTfO2PE", "7_Wbmy79wxQ ", " BVdHrTfO2PE",
                "https://www.youtube.com/watch?v=7_Wbmy79wxQ", "https://youtu.be/BVdHrTfO2PE"}) {
            assertFalse("Unexpected reminder on " + other, AdhkarContent.supportsVideo(other));
        }
    }

    @Test public void aFutureVideoCannotQualifyByAnIdPrefixOrSuffix() {
        for (String id : new String[]{"7_Wbmy79wxQ", "BVdHrTfO2PE"}) {
            assertFalse(AdhkarContent.supportsVideo(id + "a"));
            assertFalse(AdhkarContent.supportsVideo("a" + id));
            for (int i = 0; i < id.length(); i++) {
                String changed = id.substring(0, i) + (id.charAt(i) == 'a' ? 'b' : 'a') + id.substring(i + 1);
                assertFalse("Different video " + changed, AdhkarContent.supportsVideo(changed));
            }
        }
    }

    @Test public void morningStartsAtFourAndEndsAtNoonInLocalTime() {
        assertPeriod("2026-10-26T03:59:59.999Z", "UTC", AdhkarContent.Period.EVENING);
        assertPeriod("2026-10-26T04:00:00Z", "UTC", AdhkarContent.Period.MORNING);
        assertPeriod("2026-10-26T11:59:59.999Z", "UTC", AdhkarContent.Period.MORNING);
        assertPeriod("2026-10-26T12:00:00Z", "UTC", AdhkarContent.Period.EVENING);
        assertPeriod("2026-10-26T23:59:59Z", "UTC", AdhkarContent.Period.EVENING);
        assertPeriod("2026-10-27T00:00:00Z", "UTC", AdhkarContent.Period.EVENING);
    }

    @Test public void theDeviceZoneRatherThanUtcControlsTheLabel() {
        assertPeriod("2026-10-26T01:00:00Z", "Asia/Riyadh", AdhkarContent.Period.MORNING);
        assertPeriod("2026-10-26T01:00:00Z", "UTC", AdhkarContent.Period.EVENING);
        assertPeriod("2026-10-26T09:00:00Z", "Asia/Riyadh", AdhkarContent.Period.EVENING);
        assertPeriod("2026-10-26T09:00:00Z", "UTC", AdhkarContent.Period.MORNING);
        assertPeriod("2026-10-26T22:30:00Z", "Asia/Kolkata", AdhkarContent.Period.MORNING);
    }

    @Test public void everyPeriodHasCompactDistinctArabicCardsAndAClearLabel() {
        assertEquals("أذكار الصباح", AdhkarContent.Period.MORNING.label);
        assertEquals("أذكار المساء", AdhkarContent.Period.EVENING.label);
        for (AdhkarContent.Period period : AdhkarContent.Period.values()) {
            List<String> cards = AdhkarContent.cards(period);
            assertTrue(cards.size() >= 3 && cards.size() <= 4);
            assertEquals(cards.size(), new HashSet<>(cards).size());
            for (String card : cards) {
                assertFalse(card.trim().isEmpty());
                assertTrue("Quotation must fit the small card", card.length() <= 100);
                assertTrue("Arabic words only", card.matches("[\\p{IsArabic} ]+"));
            }
        }
    }

    @Test public void theTimeSpecificPrayerKeepsTheVerifiedNarrationsWording() {
        String morning = AdhkarContent.cards(AdhkarContent.Period.MORNING).get(0);
        String evening = AdhkarContent.cards(AdhkarContent.Period.EVENING).get(0);
        assertTrue(morning.startsWith("اللهم بك أصبحنا وبك أمسينا"));
        assertTrue(evening.startsWith("اللهم بك أمسينا"));
        assertFalse(evening.contains("أصبحنا"));
        assertTrue(morning.endsWith("وإليك النشور"));
        assertTrue(evening.endsWith("وإليك النشور"));
    }

    @Test public void aConsumerCannotOverwriteTheVerifiedCards() {
        try {
            AdhkarContent.cards(AdhkarContent.Period.MORNING).set(0, "replacement");
            fail("Religious source text must remain immutable");
        } catch (UnsupportedOperationException expected) {
            // A UI consumer cannot corrupt the next render or the other period.
        }
    }

    private void assertPeriod(String instant, String zone, AdhkarContent.Period expected) {
        assertEquals(expected, AdhkarContent.periodAt(Instant.parse(instant).toEpochMilli(),
                TimeZone.getTimeZone(zone)));
    }
}
