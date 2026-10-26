package com.gathin.ambienttv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A read-only calendar occurrence. Its identity uses the original recurrence start. */
public final class AgendaEvent {
    public final String id;
    public final String title;
    public final String description;
    public final long startMillis;
    public final long endMillis;
    public final boolean allDay;
    /** Empty means that the TV's reminder preference applies. */
    public final List<Integer> reminderMinutes;

    public AgendaEvent(String id, String title, String description, long startMillis,
                       long endMillis, boolean allDay, List<Integer> reminderMinutes) {
        if (id == null || title == null || description == null || reminderMinutes == null)
            throw new IllegalArgumentException("Calendar occurrence fields must not be null");
        if (endMillis < startMillis) throw new IllegalArgumentException("Calendar end precedes start");
        ArrayList<Integer> copy = new ArrayList<>();
        for (Integer minute : reminderMinutes) {
            if (minute == null || minute < 0) throw new IllegalArgumentException("Invalid reminder minutes");
            if (!copy.contains(minute)) copy.add(minute);
        }
        Collections.sort(copy);
        this.id = id;
        this.title = title;
        this.description = description;
        this.startMillis = startMillis;
        this.endMillis = endMillis;
        this.allDay = allDay;
        this.reminderMinutes = Collections.unmodifiableList(copy);
    }
}
