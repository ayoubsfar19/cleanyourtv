package com.gathin.ambienttv;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public final class AgendaReminderPolicyTest {
    private AgendaEvent event(String id, long start, boolean allDay, Integer... reminders) {
        return new AgendaEvent(id, "Rendez-vous", "", start, start + 3_600_000L, allDay,
                Arrays.asList(reminders));
    }

    @Test public void normalDeadlineIsDeliveredOnlyDuringItsGrace() {
        assertEquals(AgendaReminderPolicy.Decision.WAIT,
                AgendaReminderPolicy.decide(9_999, 10_000, 1_000, true, false));
        assertEquals(AgendaReminderPolicy.Decision.SHOW,
                AgendaReminderPolicy.decide(10_000, 10_000, 1_000, true, false));
        assertEquals(AgendaReminderPolicy.Decision.SHOW,
                AgendaReminderPolicy.decide(70_000, 10_000, 1_000, true, false));
        assertEquals(AgendaReminderPolicy.Decision.SKIP,
                AgendaReminderPolicy.decide(70_001, 10_000, 1_000, true, false));
    }

    @Test public void newlyDiscoveredOrAlreadyHandledDeadlineNeverFloods() {
        assertEquals(AgendaReminderPolicy.Decision.SKIP,
                AgendaReminderPolicy.decide(10_000, 10_000, 10_000, true, false));
        assertEquals(AgendaReminderPolicy.Decision.SKIP,
                AgendaReminderPolicy.decide(10_000, 9_999, 10_000, true, false));
        assertEquals(AgendaReminderPolicy.Decision.SKIP,
                AgendaReminderPolicy.decide(10_000, 10_000, 1_000, true, true));
    }

    @Test public void offscreenDeadlineIsSkippedAndWakeCutoffSkipsItToo() {
        assertEquals(AgendaReminderPolicy.Decision.SKIP,
                AgendaReminderPolicy.decide(10_001, 10_000, 1_000, false, false));
        assertEquals(AgendaReminderPolicy.Decision.SKIP,
                AgendaReminderPolicy.decide(10_001, 10_000, 10_001, true, false));
    }

    @Test public void keysDeduplicateAnOccurrenceAndReminderAcrossRestarts() {
        AgendaEvent a = event("uid", 1_000_000, false, 5);
        AgendaEvent copy = new AgendaEvent("uid", "Edited title", "", 1_000_000, 2_000_000, false,
                Collections.singletonList(5));
        assertEquals(AgendaReminderPolicy.reminderKey(a, 700_000),
                AgendaReminderPolicy.reminderKey(copy, 700_000));
        assertNotEquals(AgendaReminderPolicy.reminderKey(a, 700_000),
                AgendaReminderPolicy.reminderKey(event("uid", 2_000_000, false), 700_000));
        assertNotEquals(AgendaReminderPolicy.reminderKey(a, 700_000),
                AgendaReminderPolicy.reminderKey(a, 400_000));
        assertEquals(64, AgendaReminderPolicy.reminderKey(a, 700_000).length());
        assertFalse(AgendaReminderPolicy.reminderKey(a, 700_000).contains("uid"));
    }

    @Test public void allDayReminderUsesNineLocalAcrossDstAndKeepsEventMidnight() {
        ZoneId paris = ZoneId.of("Europe/Paris");
        long midnight = LocalDate.of(2026, 3, 29).atStartOfDay(paris).toInstant().toEpochMilli();
        AgendaEvent allDay = event("all-day", midnight, true, 10);
        long nine = LocalDate.of(2026, 3, 29).atTime(9, 0).atZone(paris).toInstant().toEpochMilli();
        assertEquals(nine - 600_000, AgendaReminderPolicy.reminderTime(allDay, 10, paris));
        assertEquals(midnight, allDay.startMillis);
        assertEquals(nine - 600_000, AgendaReminderPolicy.reminderTime(allDay, 10, paris));
    }

    @Test public void nativeDisplayAlarmsTakePrecedenceAndFallbackIsExplicit() {
        assertEquals(Collections.singletonList(10),
                AgendaReminderPolicy.minutes(event("uid", 1_000_000, false), 10));
        assertEquals(Arrays.asList(5, 15),
                AgendaReminderPolicy.minutes(event("uid", 1_000_000, false, 5, 15, 5), 10));
        assertEquals(Collections.singletonList(0),
                AgendaReminderPolicy.minutes(event("uid", 1_000_000, false), 0));
        assertFalse(AgendaReminderPolicy.validDefault(1));
        assertTrue(AgendaReminderPolicy.validDefault(60));
    }
}
