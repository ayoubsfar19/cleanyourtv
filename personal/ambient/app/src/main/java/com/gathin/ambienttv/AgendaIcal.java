package com.gathin.ambienttv;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded RFC 5545 reader for Google's read-only calendar feed. No network or Android APIs. */
public final class AgendaIcal {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_EVENTS = 10_000;
    private static final int MAX_OCCURRENCES = 10_000;
    private static final long MAX_HORIZON = 370L * 24 * 60 * 60 * 1000;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern DURATION = Pattern.compile("([+-]?)P(?:(\\d+)W|(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?)");
    private static final Pattern BY_DAY = Pattern.compile("([+-]?\\d{1,2})?(MO|TU|WE|TH|FR|SA|SU)");
    private static final List<String> WEEKDAYS = Arrays.asList("MO", "TU", "WE", "TH", "FR", "SA", "SU");

    private AgendaIcal() {}

    /**
     * Returns occurrences overlapping [fromMillis,toMillis), sorted by start, title and identity.
     * Malformed data, unsupported recurrence, oversized feeds and exhausted work bounds fail explicitly.
     * A query may span at most 370 days; the caller should retain its previous good cache on failure.
     */
    public static List<AgendaEvent> parse(String source, long fromMillis, long toMillis,
                                         ZoneId fallbackZone) {
        if (source == null || fallbackZone == null) throw invalid("Missing calendar or time zone");
        if (toMillis <= fromMillis || toMillis - fromMillis < 0 || toMillis - fromMillis > MAX_HORIZON)
            throw invalid("Calendar query must span between one millisecond and 370 days");
        if (source.length() > MAX_BYTES || source.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw invalid("Calendar exceeds the 4 MiB limit");
        try {
            Feed feed = read(source, fallbackZone);
            Map<String, List<Raw>> groups = new LinkedHashMap<>();
            for (Raw event : feed.events) {
                event.prepare(feed.zone);
                groups.computeIfAbsent(event.uid, ignored -> new ArrayList<>()).add(event);
            }
            ArrayList<AgendaEvent> result = new ArrayList<>();
            Budget budget = new Budget();
            for (List<Raw> group : groups.values()) expand(group, fromMillis, toMillis, result, budget);
            result.sort(Comparator.comparingLong((AgendaEvent e) -> e.startMillis)
                    .thenComparing(e -> e.title).thenComparing(e -> e.id));
            return Collections.unmodifiableList(result);
        } catch (DateTimeException | ArithmeticException error) {
            throw invalid("Invalid calendar date or duration", error);
        }
    }

    private static void expand(List<Raw> group, long from, long to, List<AgendaEvent> out, Budget budget) {
        Raw master = null;
        Map<String, Raw> exceptions = new HashMap<>();
        for (Raw event : group) {
            if (event.recurrenceId == null) master = newer(master, event);
            else {
                if (event.rule != null || !event.rdates.isEmpty() || !event.exdates.isEmpty()) throw invalid("An exception cannot contain recurrence properties");
                String key = event.recurrenceId.key();
                exceptions.put(key, newer(exceptions.get(key), event));
            }
        }
        if (master == null) {
            // An exported detached exception still has a valid original identity and concrete start.
            for (Raw event : exceptions.values()) emit(event, null, event.recurrenceId, from, to, out);
            return;
        }
        if (master.cancelled) return;
        final Raw base = master;
        Set<String> excluded = new HashSet<>();
        for (Stamp date : base.exdates) {
            if (date.allDay != base.start.allDay) throw invalid("EXDATE type differs from DTSTART");
            excluded.add(date.key());
        }
        for (Raw exception : exceptions.values()) {
            if (exception.recurrenceId.allDay != base.start.allDay)
                throw invalid("RECURRENCE-ID type differs from DTSTART");
            if (exception.start != null && exception.start.allDay != base.start.allDay)
                throw invalid("Exception DTSTART type differs from the series");
        }
        LinkedHashMap<String, Stamp> starts = new LinkedHashMap<>();
        starts.put(base.start.key(), base.start);
        long overlapAllowance = base.start.allDay
                ? Math.multiplyExact((long) base.allDayDays + 2, 86400000L)
                : base.nominalDuration ? Math.addExact(base.durationMillis, 2 * 86400000L) : base.durationMillis;
        long minimumStart = Math.subtractExact(from, overlapAllowance);
        if (base.rule != null) base.rule.generate(base.start, minimumStart, to, starts, budget);
        for (Stamp date : base.rdates) {
            if (date.allDay != base.start.allDay) throw invalid("RDATE type differs from DTSTART");
            starts.put(date.key(), date);
        }
        for (Stamp start : starts.values()) {
            if (excluded.contains(start.key()) || exceptions.containsKey(start.key())) continue;
            emit(base, null, start, from, to, out);
        }
        // Also covers occurrences moved into this window from an original start outside it.
        for (Raw exception : exceptions.values()) emit(exception, base, exception.recurrenceId, from, to, out);
    }

    private static Raw newer(Raw first, Raw second) {
        if (first == null || second.sequence > first.sequence
                || (second.sequence == first.sequence && second.updated >= first.updated)) return second;
        return first;
    }

    private static void emit(Raw raw, Raw master, Stamp original, long from, long to, List<AgendaEvent> out) {
        if (raw.cancelled) return;
        Stamp start = raw.recurrenceId == null ? original : raw.start;
        if (start == null) throw invalid("An active exception has no DTSTART");
        Raw durationSource = raw.hasEnd ? raw : master != null ? master : raw;
        long end = durationSource.endAt(start);
        long millis = start.millis();
        if (millis >= to || (end > millis ? end <= from : millis < from)) return;
        String title = raw.title != null ? raw.title : master != null && master.title != null ? master.title : "";
        String description = raw.description != null ? raw.description
                : master != null && master.description != null ? master.description : "";
        List<Integer> reminders = raw.hasAlarms ? raw.reminders
                : master != null ? master.reminders : raw.reminders;
        if (out.size() >= MAX_OCCURRENCES) throw invalid("Calendar exceeds the 10000 occurrence limit");
        out.add(new AgendaEvent(raw.uid + "/" + original.key(), title, description,
                millis, end, start.allDay, reminders));
    }

    private static Feed read(String source, ZoneId fallback) {
        ArrayList<String> lines = new ArrayList<>();
        StringBuilder logical = null;
        for (String line : source.split("\\r\\n|\\n|\\r", -1)) {
            if (line.startsWith(" ") || line.startsWith("\t")) {
                if (logical == null) throw invalid("Folded line has no preceding property");
                logical.append(line, 1, line.length());
            } else {
                if (logical != null) lines.add(logical.toString());
                logical = line.isEmpty() ? null : new StringBuilder(line);
            }
        }
        if (logical != null) lines.add(logical.toString());
        if (lines.isEmpty() || !"BEGIN:VCALENDAR".equalsIgnoreCase(lines.get(0)))
            throw invalid("Missing BEGIN:VCALENDAR");
        Feed feed = new Feed(fallback);
        ArrayList<String> stack = new ArrayList<>();
        Raw event = null;
        List<Prop> alarm = null;
        boolean ended = false;
        for (String line : lines) {
            if (ended) throw invalid("Data after END:VCALENDAR");
            Prop property = Prop.parse(line);
            if (property.name.equals("BEGIN")) {
                String component = property.value.toUpperCase(Locale.ROOT);
                if (stack.isEmpty() && !component.equals("VCALENDAR")) throw invalid("Invalid calendar structure");
                if (!stack.isEmpty() && component.equals("VCALENDAR")) throw invalid("Nested VCALENDAR");
                if (component.equals("VEVENT")) {
                    if (stack.size() != 1) throw invalid("Nested VEVENT");
                    if (feed.events.size() >= MAX_EVENTS) throw invalid("Calendar exceeds the 10000 VEVENT limit");
                    event = new Raw();
                } else if (component.equals("VALARM")) {
                    if (event == null || stack.size() != 2) throw invalid("VALARM outside VEVENT");
                    alarm = new ArrayList<>();
                    event.hasAlarms = true;
                } else if (event != null) throw invalid("Unsupported event component: " + component);
                stack.add(component);
            } else if (property.name.equals("END")) {
                if (stack.isEmpty() || !stack.get(stack.size() - 1).equalsIgnoreCase(property.value))
                    throw invalid("Unbalanced calendar component");
                String component = stack.remove(stack.size() - 1);
                if (component.equals("VALARM")) {
                    event.alarms.add(alarm);
                    alarm = null;
                } else if (component.equals("VEVENT")) {
                    feed.events.add(event);
                    event = null;
                } else if (component.equals("VCALENDAR")) ended = true;
            } else if (alarm != null) alarm.add(property);
            else if (event != null) event.properties.add(property);
            else if (stack.size() == 1 && property.name.equals("X-WR-TIMEZONE")) feed.zone = zone(text(property.value));
            else if (stack.size() == 1 && property.name.equals("VERSION") && !property.value.equals("2.0"))
                throw invalid("Unsupported iCalendar version");
        }
        if (!ended || !stack.isEmpty()) throw invalid("Missing END:VCALENDAR");
        return feed;
    }

    private static final class Feed {
        final List<Raw> events = new ArrayList<>();
        ZoneId zone;
        Feed(ZoneId zone) { this.zone = zone; }
    }

    private static final class Raw {
        final List<Prop> properties = new ArrayList<>();
        final List<List<Prop>> alarms = new ArrayList<>();
        final List<Stamp> rdates = new ArrayList<>(), exdates = new ArrayList<>();
        final List<Integer> reminders = new ArrayList<>();
        String uid, title, description;
        Stamp start, recurrenceId;
        Rule rule;
        boolean cancelled, hasEnd, hasAlarms;
        long durationMillis, updated, durationRemainderMillis;
        int allDayDays, sequence, nominalDays;
        boolean nominalDuration;

        void prepare(ZoneId fallback) {
            Map<String, Prop> single = new HashMap<>();
            for (Prop prop : properties) {
                if (Arrays.asList("UID", "SUMMARY", "DESCRIPTION", "DTSTART", "DTEND", "DURATION", "RRULE", "RECURRENCE-ID", "STATUS", "SEQUENCE").contains(prop.name)
                        && single.put(prop.name, prop) != null) throw invalid("Duplicate event property: " + prop.name);
                switch (prop.name) {
                    case "UID": uid = text(prop.value); break;
                    case "SUMMARY": title = text(prop.value); break;
                    case "DESCRIPTION": description = text(prop.value); break;
                    case "DTSTART": start = Stamp.parse(prop, fallback); break;
                    case "RECURRENCE-ID":
                        if (prop.params.containsKey("RANGE")) throw invalid("Unsupported RECURRENCE-ID RANGE");
                        recurrenceId = Stamp.parse(prop, fallback); break;
                    case "STATUS": cancelled = prop.value.equalsIgnoreCase("CANCELLED"); break;
                    case "SEQUENCE": sequence = integer(prop.value, 0, Integer.MAX_VALUE, "SEQUENCE"); break;
                    case "DTSTAMP": case "LAST-MODIFIED": updated = Math.max(updated, Stamp.parse(prop, fallback).millis()); break;
                    case "EXRULE": throw invalid("Unsupported recurrence EXRULE");
                    default: break;
                }
            }
            if (uid == null || uid.trim().isEmpty()) throw invalid("VEVENT has no UID");
            if (start == null && !cancelled) throw invalid("VEVENT has no DTSTART");
            if (start == null && recurrenceId == null && !cancelled) throw invalid("VEVENT has no date");
            Prop end = single.get("DTEND"), duration = single.get("DURATION");
            hasEnd = end != null || duration != null;
            if (end != null && duration != null) throw invalid("VEVENT contains both DTEND and DURATION");
            if (start != null) {
                if (end != null) {
                    Stamp finish = Stamp.parse(end, fallback);
                    if (finish.allDay != start.allDay) throw invalid("DTEND type differs from DTSTART");
                    durationMillis = Math.subtractExact(finish.millis(), start.millis());
                    if (start.allDay) allDayDays = Math.toIntExact(ChronoUnit.DAYS.between(start.date, finish.date));
                } else if (duration != null) {
                    long seconds = durationSeconds(duration.value);
                    if (seconds < 0) throw invalid("Negative event duration");
                    durationMillis = Math.multiplyExact(seconds, 1000);
                    Matcher parsed = DURATION.matcher(duration.value);
                    if (!parsed.matches()) throw invalid("Malformed iCalendar duration");
                    nominalDays = Math.toIntExact(parsed.group(2) != null
                            ? Math.multiplyExact(Long.parseLong(parsed.group(2)), 7)
                            : parsed.group(3) != null ? Long.parseLong(parsed.group(3)) : 0);
                    nominalDuration = nominalDays > 0;
                    durationRemainderMillis = durationMillis - Math.multiplyExact((long) nominalDays, 86400000L);
                    if (start.allDay) {
                        if (seconds % 86400 != 0) throw invalid("All-day duration must use whole days");
                        allDayDays = Math.toIntExact(seconds / 86400);
                    }
                } else if (start.allDay) allDayDays = 1;
                if (durationMillis < 0 || allDayDays < 0) throw invalid("DTEND precedes DTSTART");
            }
            if (single.containsKey("RRULE")) {
                if (start == null) throw invalid("Recurring event has no DTSTART");
                rule = Rule.parse(single.get("RRULE").value, start);
            }
            for (Prop prop : properties) {
                if (prop.name.equals("RDATE") || prop.name.equals("EXDATE")) {
                    if ("PERIOD".equalsIgnoreCase(prop.params.get("VALUE"))) throw invalid("Unsupported RDATE PERIOD");
                    for (String value : prop.value.split(",", -1)) {
                        if (rdates.size() + exdates.size() >= MAX_OCCURRENCES)
                            throw invalid("Event exceeds the 10000 recurrence date limit");
                        Stamp date = Stamp.parse(prop.withValue(value), fallback);
                        (prop.name.equals("RDATE") ? rdates : exdates).add(date);
                    }
                }
            }
            for (List<Prop> alarm : alarms) {
                String action = null;
                Prop trigger = null;
                for (Prop prop : alarm) {
                    if (prop.name.equals("ACTION")) action = prop.value;
                    if (prop.name.equals("TRIGGER")) trigger = prop;
                }
                if (!"DISPLAY".equalsIgnoreCase(action) || trigger == null) continue;
                if (trigger.params.containsKey("VALUE") && !trigger.params.get("VALUE").equalsIgnoreCase("DURATION"))
                    throw invalid("Unsupported absolute DISPLAY alarm");
                if (trigger.params.containsKey("RELATED") && !trigger.params.get("RELATED").equalsIgnoreCase("START"))
                    throw invalid("Unsupported end-relative DISPLAY alarm");
                long seconds = durationSeconds(trigger.value);
                if (seconds > 0) throw invalid("Unsupported after-start DISPLAY alarm");
                long minutes = Math.addExact(Math.negateExact(seconds), 59) / 60;
                if (minutes > 525600) throw invalid("DISPLAY alarm exceeds one year");
                int minute = (int) minutes;
                if (!reminders.contains(minute)) reminders.add(minute);
            }
        }

        long endAt(Stamp occurrence) {
            if (occurrence.allDay) return occurrence.date.plusDays(allDayDays).atStartOfDay(occurrence.zone).toInstant().toEpochMilli();
            if (nominalDuration) {
                long dayEnd = ZonedDateTime.of(occurrence.date.plusDays(nominalDays), occurrence.time, occurrence.zone)
                        .toInstant().toEpochMilli();
                return Math.addExact(dayEnd, durationRemainderMillis);
            }
            return Math.addExact(occurrence.millis(), durationMillis);
        }
    }

    private static final class Prop {
        final String name, value;
        final Map<String, String> params;
        Prop(String name, String value, Map<String, String> params) { this.name = name; this.value = value; this.params = params; }
        Prop withValue(String value) { return new Prop(name, value, params); }
        static Prop parse(String line) {
            int colon = -1;
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') quoted = !quoted;
                if (c == ':' && !quoted) { colon = i; break; }
            }
            if (colon <= 0 || quoted) throw invalid("Malformed iCalendar property");
            List<String> pieces = new ArrayList<>();
            int begin = 0;
            quoted = false;
            String header = line.substring(0, colon);
            for (int i = 0; i <= header.length(); i++) {
                if (i < header.length() && header.charAt(i) == '"') quoted = !quoted;
                if (i == header.length() || (header.charAt(i) == ';' && !quoted)) {
                    pieces.add(header.substring(begin, i)); begin = i + 1;
                }
            }
            String name = pieces.get(0).toUpperCase(Locale.ROOT);
            if (!name.matches("[A-Z0-9-]+")) throw invalid("Invalid property name");
            Map<String, String> params = new HashMap<>();
            for (int i = 1; i < pieces.size(); i++) {
                int equals = pieces.get(i).indexOf('=');
                if (equals <= 0) throw invalid("Malformed property parameter");
                String key = pieces.get(i).substring(0, equals).toUpperCase(Locale.ROOT);
                String value = pieces.get(i).substring(equals + 1);
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) value = value.substring(1, value.length() - 1);
                if (params.put(key, value) != null) throw invalid("Duplicate property parameter");
            }
            return new Prop(name, line.substring(colon + 1), params);
        }
    }

    private static final class Stamp {
        final LocalDate date;
        final LocalTime time;
        final ZoneId zone;
        final boolean allDay;
        Stamp(LocalDate date, LocalTime time, ZoneId zone, boolean allDay) { this.date = date; this.time = time; this.zone = zone; this.allDay = allDay; }
        static Stamp parse(Prop prop, ZoneId fallback) {
            String value = prop.value;
            String type = prop.params.get("VALUE");
            boolean date = "DATE".equalsIgnoreCase(type);
            if (type != null && !date && !type.equalsIgnoreCase("DATE-TIME")) throw invalid("Unsupported calendar date type");
            ZoneId zone = prop.params.containsKey("TZID") ? zone(prop.params.get("TZID")) : fallback;
            if (date) {
                if (!value.matches("\\d{8}")) throw invalid("Malformed DATE");
                return new Stamp(LocalDate.parse(value, DATE), LocalTime.MIDNIGHT, zone, true);
            }
            boolean utc = value.endsWith("Z");
            if (utc && prop.params.containsKey("TZID")) throw invalid("UTC date also contains TZID");
            if (!value.matches("\\d{8}T\\d{6}Z?")) throw invalid("Malformed DATE-TIME");
            LocalDateTime local = LocalDateTime.parse(utc ? value.substring(0, value.length() - 1) : value, TIME);
            return new Stamp(local.toLocalDate(), local.toLocalTime(), utc ? ZoneOffset.UTC : zone, false);
        }
        Stamp on(LocalDate day) { return new Stamp(day, time, zone, allDay); }
        long millis() { return allDay ? date.atStartOfDay(zone).toInstant().toEpochMilli() : ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli(); }
        String key() { return allDay ? "D" + date : Long.toString(millis()); }
        boolean validRecurrenceTime() { return allDay || !zone.getRules().getValidOffsets(LocalDateTime.of(date, time)).isEmpty(); }
    }

    private static final class DaySpec {
        final int ordinal;
        final DayOfWeek day;
        DaySpec(int ordinal, DayOfWeek day) { this.ordinal = ordinal; this.day = day; }
    }

    private static final class Rule {
        String frequency;
        int interval = 1, count = Integer.MAX_VALUE;
        Stamp until;
        DayOfWeek weekStart = DayOfWeek.MONDAY;
        final Set<Integer> months = new HashSet<>(), monthDays = new HashSet<>();
        final List<DaySpec> days = new ArrayList<>();
        final Set<Integer> positions = new TreeSet<>();

        static Rule parse(String source, Stamp start) {
            Rule rule = new Rule();
            Set<String> seen = new HashSet<>();
            for (String part : source.split(";", -1)) {
                int equals = part.indexOf('=');
                if (equals <= 0 || equals == part.length() - 1) throw invalid("Malformed RRULE");
                String key = part.substring(0, equals).toUpperCase(Locale.ROOT);
                String value = part.substring(equals + 1).toUpperCase(Locale.ROOT);
                if (!seen.add(key)) throw invalid("Duplicate RRULE part: " + key);
                switch (key) {
                    case "FREQ":
                        if (!Arrays.asList("DAILY", "WEEKLY", "MONTHLY", "YEARLY").contains(value)) throw invalid("Unsupported recurrence frequency: " + value);
                        rule.frequency = value; break;
                    case "INTERVAL": rule.interval = integer(value, 1, 10000, "INTERVAL"); break;
                    case "COUNT": rule.count = integer(value, 1, Integer.MAX_VALUE, "COUNT"); break;
                    case "UNTIL":
                        Map<String, String> params = new HashMap<>();
                        if (value.matches("\\d{8}")) params.put("VALUE", "DATE");
                        rule.until = Stamp.parse(new Prop("UNTIL", value, params), start.zone);
                        if (rule.until.allDay != start.allDay) throw invalid("UNTIL type differs from DTSTART");
                        break;
                    case "WKST":
                        if (!WEEKDAYS.contains(value)) throw invalid("Invalid WKST");
                        rule.weekStart = DayOfWeek.of(WEEKDAYS.indexOf(value) + 1); break;
                    case "BYMONTH": numbers(value, 1, 12, false, rule.months, key); break;
                    case "BYMONTHDAY": numbers(value, -31, 31, true, rule.monthDays, key); break;
                    case "BYSETPOS": numbers(value, -366, 366, true, rule.positions, key); break;
                    case "BYDAY":
                        for (String token : value.split(",", -1)) {
                            Matcher match = BY_DAY.matcher(token);
                            if (!match.matches()) throw invalid("Invalid BYDAY");
                            int ordinal = match.group(1) == null ? 0 : integer(match.group(1), -53, 53, "BYDAY ordinal");
                            if (match.group(1) != null && ordinal == 0) throw invalid("Zero BYDAY ordinal");
                            rule.days.add(new DaySpec(ordinal, DayOfWeek.of(WEEKDAYS.indexOf(match.group(2)) + 1)));
                        }
                        break;
                    default: throw invalid("Unsupported recurrence part: " + key);
                }
            }
            if (rule.frequency == null) throw invalid("RRULE has no FREQ");
            if (seen.contains("COUNT") && seen.contains("UNTIL")) throw invalid("RRULE contains both COUNT and UNTIL");
            if (rule.frequency.equals("WEEKLY") && !rule.monthDays.isEmpty()) throw invalid("BYMONTHDAY is invalid for WEEKLY recurrence");
            for (DaySpec day : rule.days) if (day.ordinal != 0 && (rule.frequency.equals("DAILY") || rule.frequency.equals("WEEKLY")))
                throw invalid("Ordinal BYDAY is invalid for DAILY or WEEKLY recurrence");
            if (!rule.positions.isEmpty() && rule.months.isEmpty() && rule.monthDays.isEmpty() && rule.days.isEmpty())
                throw invalid("BYSETPOS requires another BY part");
            return rule;
        }

        void generate(Stamp start, long minimumStart, long to, Map<String, Stamp> dates, Budget budget) {
            LocalDate period;
            switch (frequency) {
                case "WEEKLY": period = start.date.minusDays(Math.floorMod(start.date.getDayOfWeek().getValue() - weekStart.getValue(), 7)); break;
                case "MONTHLY": period = start.date.withDayOfMonth(1); break;
                case "YEARLY": period = start.date.withDayOfYear(1); break;
                default: period = start.date;
            }
            // DTSTART is part of the recurrence set and consumes one COUNT entry.
            int emitted = 1;
            LocalDate lastDay = Instant.ofEpochMilli(to).atZone(start.zone).toLocalDate().plusDays(1);
            while (!period.isAfter(lastDay) && emitted < count) {
                budget.step();
                LocalDate end;
                switch (frequency) {
                    case "WEEKLY": end = period.plusWeeks(1); break;
                    case "MONTHLY": end = period.plusMonths(1); break;
                    case "YEARLY": end = period.plusYears(1); break;
                    default: end = period.plusDays(1);
                }
                ArrayList<Stamp> candidates = new ArrayList<>();
                for (LocalDate date = period; date.isBefore(end); date = date.plusDays(1)) {
                    budget.step();
                    if (!matches(date, start.date)) continue;
                    Stamp candidate = start.on(date);
                    // RFC 5545 invalid local times are ignored and do not consume COUNT.
                    if (candidate.validRecurrenceTime()) candidates.add(candidate);
                }
                if (!positions.isEmpty()) {
                    ArrayList<Stamp> selected = new ArrayList<>();
                    Set<String> keys = new HashSet<>();
                    for (Integer position : positions) {
                        int index = position > 0 ? position - 1 : candidates.size() + position;
                        if (index >= 0 && index < candidates.size()) {
                            Stamp candidate = candidates.get(index);
                            if (keys.add(candidate.key())) selected.add(candidate);
                        }
                    }
                    selected.sort(Comparator.comparingLong(Stamp::millis));
                    candidates = selected;
                }
                for (Stamp candidate : candidates) {
                    long millis = candidate.millis();
                    if (millis <= start.millis()) continue;
                    if (until != null && millis > until.millis()) return;
                    if (millis >= to) return;
                    if (emitted >= count) return;
                    emitted++;
                    if (millis < minimumStart) continue;
                    if (dates.size() >= MAX_OCCURRENCES) throw invalid("Recurring series exceeds the 10000 occurrence expansion limit");
                    dates.put(candidate.key(), candidate);
                }
                switch (frequency) {
                    case "WEEKLY": period = period.plusWeeks(interval); break;
                    case "MONTHLY": period = period.plusMonths(interval); break;
                    case "YEARLY": period = period.plusYears(interval); break;
                    default: period = period.plusDays(interval);
                }
                if (until != null && period.atStartOfDay(start.zone).toInstant().toEpochMilli() > until.millis()) return;
            }
        }

        boolean matches(LocalDate date, LocalDate start) {
            if (!months.isEmpty() && !months.contains(date.getMonthValue())) return false;
            if (!monthDays.isEmpty() && !monthDays.contains(date.getDayOfMonth())
                    && !monthDays.contains(date.getDayOfMonth() - date.lengthOfMonth() - 1)) return false;
            if (!days.isEmpty()) {
                boolean found = false;
                for (DaySpec day : days) {
                    if (date.getDayOfWeek() != day.day) continue;
                    if (day.ordinal == 0) { found = true; break; }
                    int firstIndex, lastIndex;
                    if (frequency.equals("YEARLY") && months.isEmpty()) {
                        firstIndex = (date.getDayOfYear() - 1) / 7 + 1;
                        lastIndex = -((date.lengthOfYear() - date.getDayOfYear()) / 7 + 1);
                    } else {
                        firstIndex = (date.getDayOfMonth() - 1) / 7 + 1;
                        lastIndex = -((date.lengthOfMonth() - date.getDayOfMonth()) / 7 + 1);
                    }
                    if (day.ordinal == firstIndex || day.ordinal == lastIndex) { found = true; break; }
                }
                if (!found) return false;
            }
            switch (frequency) {
                case "WEEKLY": return !days.isEmpty() || date.getDayOfWeek() == start.getDayOfWeek();
                case "MONTHLY": return !days.isEmpty() || !monthDays.isEmpty() || date.getDayOfMonth() == start.getDayOfMonth();
                case "YEARLY":
                    if (days.isEmpty() && monthDays.isEmpty() && date.getDayOfMonth() != start.getDayOfMonth()) return false;
                    return !months.isEmpty() || !days.isEmpty() || !monthDays.isEmpty() || date.getMonth() == start.getMonth();
                default: return true;
            }
        }
    }

    private static final class Budget {
        int remaining = 500_000;
        void step() { if (--remaining < 0) throw invalid("Calendar recurrence exceeds the work limit"); }
    }

    private static void numbers(String source, int min, int max, boolean excludeZero, Set<Integer> target, String field) {
        for (String token : source.split(",", -1)) {
            int number = integer(token, min, max, field);
            if (excludeZero && number == 0) throw invalid("Zero " + field);
            target.add(number);
        }
    }
    private static int integer(String source, int min, int max, String field) {
        try {
            if (!source.matches("[+-]?\\d+")) throw invalid("Invalid " + field);
            long number = Long.parseLong(source);
            if (number < min || number > max) throw invalid("Invalid " + field);
            return (int) number;
        } catch (NumberFormatException error) { throw invalid("Invalid " + field, error); }
    }
    private static long durationSeconds(String source) {
        Matcher match = DURATION.matcher(source);
        if (!match.matches() || !source.matches(".*\\d.*") || source.endsWith("T"))
            throw invalid("Malformed iCalendar duration");
        long total = 0;
        long[] factors = {604800, 86400, 3600, 60, 1};
        try {
            for (int i = 0; i < factors.length; i++) if (match.group(i + 2) != null)
                total = Math.addExact(total, Math.multiplyExact(Long.parseLong(match.group(i + 2)), factors[i]));
        } catch (NumberFormatException error) { throw invalid("Invalid calendar duration", error); }
        return "-".equals(match.group(1)) ? Math.negateExact(total) : total;
    }
    private static ZoneId zone(String value) {
        try { return ZoneId.of(value); }
        catch (DateTimeException error) { throw invalid("Unsupported calendar time zone: " + value, error); }
    }
    private static String text(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\') { result.append(c); continue; }
            if (++i == value.length()) throw invalid("Dangling iCalendar text escape");
            char next = value.charAt(i);
            if (next == 'n' || next == 'N') result.append('\n');
            else if (next == '\\' || next == ',' || next == ';') result.append(next);
            else throw invalid("Unsupported iCalendar text escape");
        }
        return result.toString();
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private static IllegalArgumentException invalid(String message, Throwable cause) { return new IllegalArgumentException(message, cause); }
}
