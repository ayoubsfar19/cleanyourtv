package com.gathin.ambienttv;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/** Local, text-only reminders. No titles, channels, network, audio, counts or reward claims. */
final class AdhkarContent {
    enum Period {
        MORNING("أذكار الصباح"), EVENING("أذكار المساء");

        final String label;
        Period(String label) { this.label = label; }
    }

    /*
     * Original Arabic hadith wording, with vowel marks and punctuation omitted for readability.
     * Verified against these Arabic primary texts on 2026-10-26:
     * Morning/evening: Sunan Abi Dawud 5068, https://sunnah.com/abudawud:5068
     * That narration's evening wording ends in النشور and does not include وبك أصبحنا.
     * Shared general dhikr (not exclusive to a particular time):
     * https://sunnah.com/bukhari:6405 ; https://sunnah.com/bukhari:6403
     * https://sunnah.com/muslim:2695
     * Rotation is a display interval, never a prescribed repetition count or complete routine.
     */
    private static final String TASBIH = "سبحان الله وبحمده";
    private static final String TAHLIL =
            "لا إله إلا الله وحده لا شريك له له الملك وله الحمد وهو على كل شيء قدير";
    private static final String REMEMBRANCE =
            "سبحان الله والحمد لله ولا إله إلا الله والله أكبر";
    private static final List<String> MORNING = Collections.unmodifiableList(Arrays.asList(
            "اللهم بك أصبحنا وبك أمسينا وبك نحيا وبك نموت وإليك النشور",
            TASBIH, TAHLIL, REMEMBRANCE));
    private static final List<String> EVENING = Collections.unmodifiableList(Arrays.asList(
            "اللهم بك أمسينا وبك نحيا وبك نموت وإليك النشور",
            TASBIH, TAHLIL, REMEMBRANCE));

    private AdhkarContent() { }

    /** Exact owner-selected IDs only. Renames and other videos from the same channel do not qualify. */
    static boolean supportsVideo(String videoId) {
        return "7_Wbmy79wxQ".equals(videoId) || "BVdHrTfO2PE".equals(videoId);
    }

    /** 04:00–11:59 local time is a UI heuristic, not a religious ruling about dhikr times. */
    static Period periodAt(long epochMillis, TimeZone zone) {
        Calendar local = Calendar.getInstance(zone);
        local.setTimeInMillis(epochMillis);
        int hour = local.get(Calendar.HOUR_OF_DAY);
        return hour >= 4 && hour < 12 ? Period.MORNING : Period.EVENING;
    }

    static List<String> cards(Period period) {
        return period == Period.MORNING ? MORNING : EVENING;
    }
}
