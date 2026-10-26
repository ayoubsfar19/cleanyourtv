package com.gathin.ambienttv;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public final class AgendaReminderPolicyTest {
    private static final ZoneId UTC = ZoneId.of("UTC");

    private AgendaEvent event(String id, long start, boolean allDay, Integer... reminders) {
        return event(id, start, start + 3_600_000L, allDay, reminders);
    }

    private AgendaEvent event(String id, long start, long end, boolean allDay, Integer... reminders) {
        return new AgendaEvent(id, "Rendez-vous", "", start, end, allDay,
                Arrays.asList(reminders));
    }

    private void assertReminder(AgendaReminderPolicy.Reminder reminder, long when, String stage) {
        assertEquals(when, reminder.when);
        assertEquals(stage, reminder.stage);
    }

    @Test public void repeatedTimedEventSpreadsThreeRemindersAcrossItsDuration() {
        long start = Instant.parse("2026-10-26T10:00:00Z").toEpochMilli();
        AgendaEvent longEvent = event("long", start, start + 7_200_000L, false, 5, 15);
        List<AgendaReminderPolicy.Reminder> longReminders =
                AgendaReminderPolicy.reminders(longEvent, 30, UTC, true);
        assertEquals(3, longReminders.size());
        assertReminder(longReminders.get(0), start, "start");
        assertReminder(longReminders.get(1), start + 3_600_000L, "middle");
        assertReminder(longReminders.get(2), start + 7_200_000L, "end");

        AgendaEvent shortEvent = event("short", start, start + 1_200_000L, false);
        List<AgendaReminderPolicy.Reminder> shortReminders =
                AgendaReminderPolicy.reminders(shortEvent, 60, UTC, true);
        assertEquals(3, shortReminders.size());
        assertReminder(shortReminders.get(1), start + 600_000L, "middle");
        assertReminder(shortReminders.get(2), start + 1_200_000L, "end");
    }

    @Test public void midpointRoundsTowardStartForOddMillisecondsWithoutOverflow() {
        List<AgendaReminderPolicy.Reminder> odd = AgendaReminderPolicy.reminders(
                event("odd", 1_000_001, 1_010_002, false), 10, UTC, true);
        assertEquals(3, odd.size());
        assertReminder(odd.get(1), 1_005_001, "middle");

        List<AgendaReminderPolicy.Reminder> wide = AgendaReminderPolicy.reminders(
                event("wide", Long.MIN_VALUE, Long.MAX_VALUE, false), 10, UTC, true);
        assertEquals(3, wide.size());
        assertReminder(wide.get(0), Long.MIN_VALUE, "start");
        assertReminder(wide.get(1), -1, "middle");
        assertReminder(wide.get(2), Long.MAX_VALUE, "end");
    }

    @Test public void midpointUsesElapsedTimeAcrossMidnightAndDstChange() {
        ZoneId paris = ZoneId.of("Europe/Paris");
        long start = Instant.parse("2026-10-24T22:30:00Z").toEpochMilli();
        long end = Instant.parse("2026-10-25T02:30:00Z").toEpochMilli();
        List<AgendaReminderPolicy.Reminder> reminders = AgendaReminderPolicy.reminders(
                event("overnight", start, end, false), 10, paris, true);
        assertEquals(3, reminders.size());
        assertReminder(reminders.get(0), start, "start");
        assertReminder(reminders.get(1), Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(),
                "middle");
        assertReminder(reminders.get(2), end, "end");
    }

    @Test public void zeroAndVeryShortEventsNeverDuplicateADueInstant() {
        List<AgendaReminderPolicy.Reminder> zero = AgendaReminderPolicy.reminders(
                event("zero", 1_000, 1_000, false), 10, UTC, true);
        assertEquals(1, zero.size());
        assertReminder(zero.get(0), 1_000, "start");

        List<AgendaReminderPolicy.Reminder> one = AgendaReminderPolicy.reminders(
                event("one", 1_000, 1_001, false), 10, UTC, true);
        assertEquals(2, one.size());
        assertReminder(one.get(0), 1_000, "start");
        assertReminder(one.get(1), 1_001, "end");

        List<AgendaReminderPolicy.Reminder> two = AgendaReminderPolicy.reminders(
                event("two", 1_000, 1_002, false), 10, UTC, true);
        assertEquals(3, two.size());
        assertReminder(two.get(1), 1_001, "middle");
    }

    @Test public void singleModeKeepsNativeAdvanceAlarmsAndFallback() {
        AgendaEvent nativeAlarms = event("native", 1_000_000, false, 5, 15, 5);
        List<AgendaReminderPolicy.Reminder> reminders = AgendaReminderPolicy.reminders(
                nativeAlarms, 30, UTC, false);
        assertEquals(2, reminders.size());
        assertReminder(reminders.get(0), 100_000, "before");
        assertReminder(reminders.get(1), 700_000, "before");
        for (AgendaReminderPolicy.Reminder reminder : reminders) {
            assertEquals(AgendaReminderPolicy.reminderKey(nativeAlarms, reminder.when),
                    AgendaReminderPolicy.reminderKey(nativeAlarms, reminder));
        }

        AgendaEvent fallback = event("fallback", 1_000_000, false);
        List<AgendaReminderPolicy.Reminder> fallbackReminders = AgendaReminderPolicy.reminders(
                fallback, 10, UTC, false);
        assertEquals(1, fallbackReminders.size());
        assertReminder(fallbackReminders.get(0), 400_000, "before");
        assertEquals(AgendaReminderPolicy.reminderKey(fallback, 400_000),
                AgendaReminderPolicy.reminderKey(fallback, fallbackReminders.get(0)));
    }

    @Test public void allDayEventsKeepNineOClockAlarmsInRepeatedMode() {
        ZoneId paris = ZoneId.of("Europe/Paris");
        LocalDate date = LocalDate.of(2026, 3, 29);
        long midnight = date.atStartOfDay(paris).toInstant().toEpochMilli();
        long end = date.plusDays(1).atStartOfDay(paris).toInstant().toEpochMilli();
        long nine = date.atTime(9, 0).atZone(paris).toInstant().toEpochMilli();
        AgendaEvent nativeAlarms = event("all-day", midnight, end, true, 5, 30);
        List<AgendaReminderPolicy.Reminder> reminders = AgendaReminderPolicy.reminders(
                nativeAlarms, 10, paris, true);
        assertEquals(2, reminders.size());
        assertReminder(reminders.get(0), nine - 1_800_000L, "before");
        assertReminder(reminders.get(1), nine - 300_000L, "before");
        assertEquals(midnight, nativeAlarms.startMillis);
        for (AgendaReminderPolicy.Reminder reminder : reminders) {
            assertEquals(AgendaReminderPolicy.reminderKey(nativeAlarms, reminder.when),
                    AgendaReminderPolicy.reminderKey(nativeAlarms, reminder));
        }

        List<AgendaReminderPolicy.Reminder> fallback = AgendaReminderPolicy.reminders(
                event("all-day-fallback", midnight, end, true), 10, paris, true);
        assertEquals(1, fallback.size());
        assertReminder(fallback.get(0), nine - 600_000L, "before");
    }

    @Test public void endEditPreservesConsumedStartAndRearmsMovedPhases() {
        AgendaEvent original = event("uid", 1_000, 7_000, false);
        AgendaEvent edited = new AgendaEvent("uid", "Edited title", "Edited description", 1_000,
                9_000, false, Collections.singletonList(30));
        List<AgendaReminderPolicy.Reminder> before = AgendaReminderPolicy.reminders(
                original, 10, UTC, true);
        List<AgendaReminderPolicy.Reminder> after = AgendaReminderPolicy.reminders(
                edited, 15, UTC, true);
        assertEquals(AgendaReminderPolicy.reminderKey(original, before.get(0)),
                AgendaReminderPolicy.reminderKey(edited, after.get(0)));
        assertNotEquals(AgendaReminderPolicy.reminderKey(original, before.get(1)),
                AgendaReminderPolicy.reminderKey(edited, after.get(1)));
        assertNotEquals(AgendaReminderPolicy.reminderKey(original, before.get(2)),
                AgendaReminderPolicy.reminderKey(edited, after.get(2)));

        AgendaEvent renamed = new AgendaEvent("uid", "Edited title", "", 1_000, 7_000, false,
                Collections.emptyList());
        List<AgendaReminderPolicy.Reminder> copy = AgendaReminderPolicy.reminders(
                renamed, 10, UTC, true);
        for (int i = 0; i < before.size(); i++) {
            assertEquals(AgendaReminderPolicy.reminderKey(original, before.get(i)),
                    AgendaReminderPolicy.reminderKey(renamed, copy.get(i)));
        }
    }

    @Test public void phaseKeysDistinguishStageDueTimeAndOccurrence() {
        AgendaEvent original = event("uid", 1_000, 7_000, false);
        AgendaReminderPolicy.Reminder start = AgendaReminderPolicy.reminders(
                original, 0, UTC, true).get(0);
        String key = AgendaReminderPolicy.reminderKey(original, start);
        assertNotEquals(key, AgendaReminderPolicy.reminderKey(original,
                new AgendaReminderPolicy.Reminder(start.when, "middle")));
        assertNotEquals(key, AgendaReminderPolicy.reminderKey(original,
                new AgendaReminderPolicy.Reminder(start.when + 1, "start")));
        assertNotEquals(key, AgendaReminderPolicy.reminderKey(original,
                AgendaReminderPolicy.reminders(original, 0, UTC, false).get(0)));
        assertNotEquals(key, AgendaReminderPolicy.reminderKey(
                event("other", 1_000, 7_000, false), start));
        assertNotEquals(key, AgendaReminderPolicy.reminderKey(
                event("uid", 2_000, 8_000, false), start));
        assertEquals(64, key.length());
        assertFalse(key.contains("uid"));
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
