package com.gathin.ambienttv;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class AgendaIcalTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final String FROM = "2026-01-01T00:00:00Z";
    private static final String TO = "2027-01-01T00:00:00Z";

    @Test public void unfoldedNonAsciiAndTextEscapesPreserveUserContent() {
        List<AgendaEvent> events = parse(event("UID:texte\nDTSTART:20261026T090000Z\nDTEND:20261026T100000Z\n"
                + "SUMMARY:Café et موعد\\, famille\\; maison\nDESCRIPTION:Ligne une\\nDeuxième \\\\ chemin\n  suite"));
        AgendaEvent result = events.get(0);
        assertEquals("Café et موعد, famille; maison", result.title);
        assertEquals("Ligne une\nDeuxième \\ chemin suite", result.description);
        assertEquals(instant("2026-10-26T09:00:00Z"), result.startMillis);
        assertEquals(instant("2026-10-26T10:00:00Z"), result.endMillis);
        assertEquals("texte/" + result.startMillis, result.id);
    }

    @Test public void localAndUtcEventsSortIntoTheSameTimeline() {
        List<AgendaEvent> events = parse(event("UID:utc\nDTSTART:20261026T080000Z\nSUMMARY:A")
                + event("UID:zone\nDTSTART;TZID=\"Europe/Paris\":20261026T100000\nSUMMARY:B")
                + event("UID:float\nDTSTART:20261026T100000\nSUMMARY:C"));
        assertEquals(instant("2026-10-26T08:00:00Z"), events.get(0).startMillis);
        assertEquals(instant("2026-10-26T09:00:00Z"), events.get(1).startMillis);
        assertEquals(instant("2026-10-26T10:00:00Z"), events.get(2).startMillis);
    }

    @Test public void calendarZoneControlsFloatingTimesAndAllDayDates() {
        String source = "BEGIN:VCALENDAR\nVERSION:2.0\nX-WR-TIMEZONE:Africa/Casablanca\n"
                + event("UID:float\nDTSTART:20261026T100000")
                + event("UID:day\nDTSTART;VALUE=DATE:20261027\nDTEND;VALUE=DATE:20261029")
                + "END:VCALENDAR\n";
        List<AgendaEvent> events = AgendaIcal.parse(source, instant(FROM), instant(TO), UTC);
        ZoneId calendarZone = ZoneId.of("Africa/Casablanca");
        assertEquals(LocalDateTime.parse("2026-10-26T10:00:00").atZone(calendarZone).toInstant().toEpochMilli(), events.get(0).startMillis);
        AgendaEvent day = events.get(1);
        assertTrue(day.allDay);
        assertEquals(LocalDateTime.parse("2026-10-27T00:00:00").atZone(calendarZone).toInstant().toEpochMilli(), day.startMillis);
        assertEquals(LocalDateTime.parse("2026-10-29T00:00:00").atZone(calendarZone).toInstant().toEpochMilli(), day.endMillis);
    }

    @Test public void allDayDefaultDurationSpansTheDstCalendarDay() {
        List<AgendaEvent> events = parse(event("UID:day\nDTSTART;VALUE=DATE:20260329"), "Europe/Paris");
        assertEquals(23L * 3600000, events.get(0).endMillis - events.get(0).startMillis);
        List<AgendaEvent> explicit = parse(event("UID:day\nDTSTART;VALUE=DATE:20260329\nDURATION:P2D"), "Europe/Paris");
        assertEquals(47L * 3600000, explicit.get(0).endMillis - explicit.get(0).startMillis);
    }

    @Test public void nominalDayDurationAndExactHoursHaveDifferentDstSemantics() {
        List<AgendaEvent> nominal = parse(event("UID:day\nDTSTART;TZID=Europe/Paris:20260328T120000\nDURATION:P1D"));
        List<AgendaEvent> exact = parse(event("UID:hours\nDTSTART;TZID=Europe/Paris:20260328T120000\nDURATION:PT24H"));
        assertEquals(23L * 3600000, nominal.get(0).endMillis - nominal.get(0).startMillis);
        assertEquals(24L * 3600000, exact.get(0).endMillis - exact.get(0).startMillis);
    }

    @Test public void heavilyFoldedDescriptionIsReadInLinearWork() {
        StringBuilder folded = new StringBuilder("UID:fold\nDTSTART:20261026T090000Z\nDESCRIPTION:a");
        for (int i = 0; i < 20000; i++) folded.append("\n b");
        assertEquals(20001, parse(event(folded.toString())).get(0).description.length());
    }

    @Test public void dailyRecurrenceKeepsLocalClockAcrossEuropeanDst() {
        List<AgendaEvent> events = parse(event("UID:daily\nDTSTART;TZID=Europe/Paris:20260328T090000\n"
                + "DTEND;TZID=Europe/Paris:20260328T100000\nRRULE:FREQ=DAILY;COUNT=3"));
        assertEquals(3, events.size());
        assertEquals(instant("2026-03-28T08:00:00Z"), events.get(0).startMillis);
        assertEquals(instant("2026-03-29T07:00:00Z"), events.get(1).startMillis);
        assertEquals(instant("2026-03-30T07:00:00Z"), events.get(2).startMillis);
        assertEquals(3600000L, events.get(1).endMillis - events.get(1).startMillis);
    }

    @Test public void nonexistentRecurrenceLocalTimeDoesNotConsumeCount() {
        List<AgendaEvent> events = parse(event("UID:gap\nDTSTART;TZID=Europe/Paris:20260328T023000\nRRULE:FREQ=DAILY;COUNT=3"));
        assertEquals(3, events.size());
        assertEquals(instant("2026-03-30T00:30:00Z"), events.get(1).startMillis);
        assertEquals(instant("2026-03-31T00:30:00Z"), events.get(2).startMillis);
    }

    @Test public void moroccoRamadanTransitionUsesZoneRulesInsteadOfFixedOffset() {
        List<AgendaEvent> events = parse(event("UID:maroc\nDTSTART;TZID=Africa/Casablanca:20260214T090000\nRRULE:FREQ=DAILY;COUNT=3"));
        assertEquals(instant("2026-02-14T08:00:00Z"), events.get(0).startMillis);
        assertEquals(instant("2026-02-15T09:00:00Z"), events.get(1).startMillis);
        assertEquals(instant("2026-02-16T09:00:00Z"), events.get(2).startMillis);
    }

    @Test public void weeklyIntervalsRespectWeekStartAndByDay() {
        String start = "UID:weeks\nDTSTART:20260802T090000Z\nRRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=4;BYDAY=SU,MO;WKST=SU";
        List<AgendaEvent> events = parse(event(start));
        assertDates(events, "2026-08-02", "2026-08-03", "2026-08-16", "2026-08-17");
    }

    @Test public void monthlyOrdinalAndLastWeekdaySetPositionExpandCorrectly() {
        List<AgendaEvent> ordinal = parse(event("UID:month\nDTSTART:20260112T090000Z\nRRULE:FREQ=MONTHLY;COUNT=3;BYDAY=2MO"));
        assertDates(ordinal, "2026-01-12", "2026-02-09", "2026-03-09");
        List<AgendaEvent> lastWeekday = parse(event("UID:month\nDTSTART:20260130T090000Z\n"
                + "RRULE:FREQ=MONTHLY;COUNT=3;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1"));
        assertDates(lastWeekday, "2026-01-30", "2026-02-27", "2026-03-31");
    }

    @Test public void monthlyMissingDayIsSkippedAndCountNotConsumed() {
        assertDates(parse(event("UID:month\nDTSTART:20260131T090000Z\nRRULE:FREQ=MONTHLY;COUNT=4")),
                "2026-01-31", "2026-03-31", "2026-05-31", "2026-07-31");
    }

    @Test public void negativeMonthDaysAndUntilAreInclusive() {
        assertDates(parse(event("UID:last\nDTSTART:20260131T090000Z\nRRULE:FREQ=MONTHLY;BYMONTHDAY=-1;UNTIL=20260331T090000Z")),
                "2026-01-31", "2026-02-28", "2026-03-31");
    }

    @Test public void yearlyByMonthAndOrdinalByDay() {
        List<AgendaEvent> events = parse(event("UID:year\nDTSTART:20260105T090000Z\n"
                + "RRULE:FREQ=YEARLY;BYMONTH=1,6;BYDAY=1MO;COUNT=3"));
        assertDates(events, "2026-01-05", "2026-06-01");
        assertDates(parse(event("UID:year\nDTSTART:20260105T090000Z\nRRULE:FREQ=YEARLY;BYDAY=1MO;COUNT=3")), "2026-01-05");
    }

    @Test public void dailySelectorsAndYearlySetPositionApplyToWholePeriods() {
        assertDates(parse(event("UID:filter\nDTSTART:20260105T090000Z\n"
                + "RRULE:FREQ=DAILY;INTERVAL=2;BYDAY=MO,WE,FR;BYMONTH=1;COUNT=4")),
                "2026-01-05", "2026-01-07", "2026-01-09", "2026-01-19");
        assertDates(parse(event("UID:year\nDTSTART:20260105T090000Z\n"
                + "RRULE:FREQ=YEARLY;BYMONTH=1,6;BYDAY=MO;BYSETPOS=1,-1;COUNT=4")),
                "2026-01-05", "2026-06-29");
    }

    @Test public void rdateExdateAndCancelledOrMovedExceptionsKeepOriginalIdentity() {
        String base = event("UID:series\nDTSTART:20261026T090000Z\nDTEND:20261026T100000Z\nSUMMARY:Original\nDESCRIPTION:Inherited\n"
                + "RRULE:FREQ=DAILY;COUNT=5\nEXDATE:20261027T090000Z\nRDATE:20261031T090000Z,20261031T090000Z");
        String moved = event("UID:series\nRECURRENCE-ID:20261028T090000Z\nDTSTART:20261028T120000Z\nSUMMARY:Moved");
        String cancelled = event("UID:series\nRECURRENCE-ID:20261029T090000Z\nSTATUS:CANCELLED");
        List<AgendaEvent> events = parse(base + moved + cancelled);
        assertDates(events, "2026-10-26", "2026-10-28", "2026-10-30", "2026-10-31");
        AgendaEvent exception = events.get(1);
        assertEquals(instant("2026-10-28T12:00:00Z"), exception.startMillis);
        assertEquals("series/" + instant("2026-10-28T09:00:00Z"), exception.id);
        assertEquals("Inherited", exception.description);
        assertEquals(3600000L, exception.endMillis - exception.startMillis);
    }

    @Test public void anOverrideCanMoveIntoWindowFromAnEarlierOriginalStart() {
        String base = event("UID:series\nDTSTART:20260101T090000Z\nRRULE:FREQ=DAILY;COUNT=2");
        String moved = event("UID:series\nRECURRENCE-ID:20260102T090000Z\nDTSTART:20261028T120000Z");
        List<AgendaEvent> events = parseBetween(base + moved, "2026-10-28T00:00:00Z", "2026-10-29T00:00:00Z", UTC);
        assertEquals(1, events.size());
        assertEquals("series/" + instant("2026-01-02T09:00:00Z"), events.get(0).id);
    }

    @Test public void cancelledSeriesAndHighestSequenceExceptionAreHandled() {
        assertTrue(parse(event("UID:gone\nSTATUS:CANCELLED")).isEmpty());
        String base = event("UID:a\nDTSTART:20261026T090000Z\nRRULE:FREQ=DAILY;COUNT=2");
        String obsolete = event("UID:a\nRECURRENCE-ID:20261027T090000Z\nDTSTART:20261027T100000Z\nSEQUENCE:1");
        String newest = event("UID:a\nRECURRENCE-ID:20261027T090000Z\nDTSTART:20261027T120000Z\nSEQUENCE:2");
        assertEquals(instant("2026-10-27T12:00:00Z"), parse(base + newest + obsolete).get(1).startMillis);
    }

    @Test public void displayAlarmsAreCopiedAndMissingAlarmsRemainEmpty() {
        String alarms = "BEGIN:VALARM\nACTION:DISPLAY\nTRIGGER:-P0DT0H10M0S\nEND:VALARM\n"
                + "BEGIN:VALARM\nACTION:DISPLAY\nTRIGGER:-PT90S\nEND:VALARM\n"
                + "BEGIN:VALARM\nACTION:EMAIL\nTRIGGER:-P1D\nEND:VALARM\n"
                + "BEGIN:VALARM\nACTION:DISPLAY\nTRIGGER:-PT10M\nEND:VALARM";
        List<AgendaEvent> events = parse(event("UID:alarm\nDTSTART:20261026T090000Z\n" + alarms));
        assertEquals(Arrays.asList(2, 10), events.get(0).reminderMinutes);
        assertTrue(parse(event("UID:none\nDTSTART:20261026T090000Z")).get(0).reminderMinutes.isEmpty());
        try { events.get(0).reminderMinutes.add(20); fail(); }
        catch (UnsupportedOperationException expected) { }
        try { events.clear(); fail(); }
        catch (UnsupportedOperationException expected) { }
        ArrayList<Integer> input = new ArrayList<>(Collections.singletonList(5));
        AgendaEvent copy = new AgendaEvent("a", "b", "c", 0, 0, false, input);
        input.add(10);
        assertEquals(Collections.singletonList(5), copy.reminderMinutes);
    }

    @Test public void windowIncludesOverlappingEventsAndExcludesExclusiveEnd() {
        String events = event("UID:overlap\nDTSTART:20261025T230000Z\nDTEND:20261026T010000Z")
                + event("UID:end\nDTSTART:20261025T230000Z\nDTEND:20261026T000000Z")
                + event("UID:atstart\nDTSTART:20261026T000000Z")
                + event("UID:after\nDTSTART:20261027T000000Z");
        List<AgendaEvent> result = parseBetween(events, "2026-10-26T00:00:00Z", "2026-10-27T00:00:00Z", UTC);
        assertEquals(2, result.size());
        assertTrue(result.get(0).id.startsWith("overlap/"));
        assertTrue(result.get(1).id.startsWith("atstart/"));
    }

    @Test public void oldDailySeriesDoesNotFillOutputWithPastOccurrences() {
        List<AgendaEvent> events = parseBetween(event("UID:old\nDTSTART:19800101T090000Z\nRRULE:FREQ=DAILY"),
                "2026-10-26T00:00:00Z", "2026-10-28T00:00:00Z", UTC);
        assertDates(events, "2026-10-26", "2026-10-27");
    }

    @Test public void invalidDatesStructuresAndUnknownZonesFailExplicitly() {
        reject("BEGIN:VCALENDAR\n" + event("UID:date\nDTSTART:20260230T090000Z") + "END:VCALENDAR", "date");
        reject("BEGIN:VCALENDAR\n" + event("UID:date\nDTSTART;TZID=Unknown/Place:20261026T090000") + "END:VCALENDAR", "time zone");
        reject("BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:x\nDTSTART:20261026T090000Z\nEND:VCALENDAR", "Unbalanced");
        reject(calendar(event("UID:x\nDTSTART:20261026T090000Z\nDTEND:20261026T080000Z")), "precedes");
        reject(calendar(event("UID:x\nDTSTART:20261026T090000Z\nDTSTART:20261027T090000Z")), "Duplicate");
        for (String duration : Arrays.asList("+P", "+PT", "P1DT", "-P", "PT"))
            reject(calendar(event("UID:duration\nDTSTART:20261026T090000Z\nDURATION:" + duration)), "duration");
        reject("<html>login</html>", "BEGIN:VCALENDAR");
    }

    @Test public void unsupportedRecurrenceAndInvalidRuleCombinationsCannotLookComplete() {
        for (String rule : Arrays.asList("FREQ=HOURLY", "FREQ=DAILY;BYHOUR=9", "FREQ=YEARLY;BYYEARDAY=1",
                "FREQ=WEEKLY;BYMONTHDAY=1", "FREQ=DAILY;BYDAY=1MO", "FREQ=DAILY;COUNT=0",
                "FREQ=DAILY;COUNT=2;UNTIL=20261028T090000Z", "FREQ=DAILY;BYSETPOS=1")) {
            reject(calendar(event("UID:x\nDTSTART:20261026T090000Z\nRRULE:" + rule)), null);
        }
        reject(calendar(event("UID:x\nDTSTART:20261026T090000Z\nEXRULE:FREQ=DAILY")), "EXRULE");
        reject(calendar(event("UID:x\nDTSTART:20261026T090000Z\nRECURRENCE-ID;RANGE=THISANDFUTURE:20261026T090000Z")), "RANGE");
        reject(calendar(event("UID:x\nDTSTART:20261026T090000Z\nRDATE;VALUE=PERIOD:20261027T090000Z/PT1H")), "PERIOD");
    }

    @Test public void excessiveFeedQueryAndExpansionFailWithinBounds() {
        try {
            AgendaIcal.parse(calendar(""), instant(FROM), instant("2028-01-01T00:00:00Z"), UTC);
            fail();
        } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("370")); }
        char[] large = new char[4 * 1024 * 1024 + 1];
        Arrays.fill(large, 'x');
        reject(new String(large), "4 MiB");
        StringBuilder tooMany = new StringBuilder();
        for (int i = 0; i < 10001; i++) tooMany.append(event("UID:" + i + "\nDTSTART:20261026T090000Z"));
        reject(calendar(tooMany.toString()), "10000 VEVENT");
    }

    private static void assertDates(List<AgendaEvent> events, String... dates) {
        List<String> actual = new ArrayList<>();
        for (AgendaEvent event : events) actual.add(Instant.ofEpochMilli(event.startMillis).atZone(UTC).toLocalDate().toString());
        assertEquals(Arrays.asList(dates), actual);
    }
    private static long instant(String value) { return Instant.parse(value).toEpochMilli(); }
    private static String event(String properties) { return "BEGIN:VEVENT\n" + properties + "\nEND:VEVENT\n"; }
    private static String calendar(String events) { return "BEGIN:VCALENDAR\nVERSION:2.0\n" + events + "END:VCALENDAR\n"; }
    private static List<AgendaEvent> parse(String events) { return parse(events, "UTC"); }
    private static List<AgendaEvent> parse(String events, String zone) { return parseBetween(events, FROM, TO, ZoneId.of(zone)); }
    private static List<AgendaEvent> parseBetween(String events, String from, String to, ZoneId zone) {
        return AgendaIcal.parse(calendar(events), instant(from), instant(to), zone);
    }
    private static void reject(String source, String fragment) {
        try {
            AgendaIcal.parse(source, instant(FROM), instant(TO), UTC);
            fail("Expected explicit rejection");
        } catch (IllegalArgumentException expected) {
            if (fragment != null) assertTrue(expected.getMessage(), expected.getMessage().toLowerCase().contains(fragment.toLowerCase()));
        }
    }
}
