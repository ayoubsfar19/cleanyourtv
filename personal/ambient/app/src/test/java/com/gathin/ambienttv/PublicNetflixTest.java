package com.gathin.ambienttv;

import org.junit.Test;
import java.time.LocalDate;
import java.util.List;
import static org.junit.Assert.*;

public class PublicNetflixTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    private static final String HEADER = "week\tcategory\tweekly_rank\tshow_title\tseason_title\n";

    @Test public void parsesBomQuotedTitlesAndAvoidsRepeatedSeasonName() {
        List<PublicNetflix.Item> rows = PublicNetflix.parse("\uFEFF" + HEADER
                + "2026-10-18\tTV (English)\t1\t\"A \"\"Great\"\"\tShow\"\tN/A\n"
                + "2026-10-18\tTV (English)\t2\tWednesday\tWednesday: Season 2\n", TODAY);
        assertEquals(2, rows.size());
        assertEquals("A \"Great\" Show", rows.get(0).title);
        assertEquals("Wednesday: Season 2", rows.get(1).title);
        assertTrue(rows.get(0).description.contains("Semaine du 18/10/2026 · Top 10 mondial · À retrouver dans Netflix"));
        assertTrue(rows.get(0).description.contains("Séries en anglais"));
    }

    @Test public void ignoresFutureAndExpiredWeeksButKeeps21DayBoundary() {
        assertTrue(PublicNetflix.parse(HEADER
                + "2026-10-03\tFilms (English)\t1\tExpired\tN/A\n"
                + "2026-10-26\tFilms (English)\t1\tFuture\tN/A\n", TODAY).isEmpty());
        List<PublicNetflix.Item> rows = PublicNetflix.parse(HEADER
                + "2026-10-04\tFilms (English)\t1\tBoundary\tN/A\n"
                + "2026-10-26\tFilms (English)\t1\tFuture\tN/A\n", TODAY);
        assertEquals("Boundary", rows.get(0).title);
    }

    @Test public void latestWeekWinsAndRankQuotasApplyAcrossFourCategories() {
        StringBuilder tsv = new StringBuilder(HEADER);
        tsv.append("2026-10-11\tFilms (English)\t1\tOld\tN/A\n");
        String[] categories = {"Films (English)", "Films (Non-English)", "TV (English)", "TV (Non-English)"};
        for (String category : categories) {
            for (int rank = 5; rank >= 1; rank--) {
                tsv.append("2026-10-18\t").append(category).append('\t').append(rank)
                        .append('\t').append(category).append(' ').append(rank).append("\tN/A\n");
            }
        }
        tsv.append("2026-10-18\tFilms (English)\t0\tInvalid\tN/A\n");
        tsv.append("2026-10-18\tFilms (English)\t1\tDuplicate rank\tN/A\n");
        List<PublicNetflix.Item> rows = PublicNetflix.parse(tsv.toString(), TODAY);
        assertEquals(12, rows.size());
        for (int i = 0; i < 12; i++) assertTrue(rows.get(i).title.endsWith(" " + (i % 3 + 1)));
        assertTrue(rows.get(3).description.contains("Films internationaux"));
        assertTrue(rows.get(9).description.contains("Séries internationales"));
    }

    @Test public void requiresTitleAndColumnsAndOnlyAcceptsNetflixOwner() {
        assertTrue(PublicNetflix.parse("week\tshow_title\n2026-10-18\tOops", TODAY).isEmpty());
        assertTrue(PublicNetflix.parse(HEADER + "2026-10-18\tFilms (English)\t1\t  \tN/A\n", TODAY).isEmpty());
        assertTrue(PublicNetflix.rows(null, "another.package").isEmpty());
    }
}
