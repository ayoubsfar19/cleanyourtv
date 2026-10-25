package com.gathin.personalphotos;
import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import static org.junit.Assert.*;

public class FootballTest {
    private static final long NOW=Instant.parse("2026-10-25T00:00:00Z").toEpochMilli();
    private JSONArray snapshots() throws Exception {
        try(java.io.InputStream in=getClass().getResourceAsStream("/kooora-snapshots.json")) {
            assertNotNull(in);
            return new JSONArray(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    @Test public void allSevenRealPageSnapshotsSelectTheirLatestCompletedMatch() throws Exception {
        JSONArray pages=snapshots();assertEquals(7,pages.length());
        for(int i=0;i<7;i++) {
            JSONObject page=pages.getJSONObject(i);
            Football.Match match=Football.select(page,i,NOW);
            assertEquals(page.getString("expected"),match.id);
            assertEquals("RESULT",match.status);
            assertTrue(match.homeScore>=0 && match.awayScore>=0);
        }
    }
    @Test public void liveTakesPriorityAndStaleLiveNeverDoes() throws Exception {
        JSONObject page=snapshots().getJSONObject(0);
        JSONArray matches=page.getJSONObject("props").getJSONObject("pageProps").getJSONObject("data").getJSONArray("matches");
        JSONObject live=new JSONObject(matches.getJSONObject(0).toString());
        live.put("id","test-live").put("status","LIVE").put("startDate","2026-10-24T23:30:00Z")
                .put("score",new JSONObject().put("teamA",1).put("teamB",0));
        matches.put(live);
        assertEquals("test-live",Football.select(page,0,NOW).id);
        live.put("startDate","2026-10-20T23:30:00Z");
        assertEquals(page.getString("expected"),Football.select(page,0,NOW).id);
    }
    @Test public void wrongClubIsRejected() throws Exception {
        try { Football.select(snapshots().getJSONObject(0),1,NOW);fail("Wrong team must fail"); }
        catch(java.io.IOException expected) { }
    }
    private Football.Match match(String id,String status,int own,int opponent) {
        return new Football.Match(id,status,"Barça","Autre","2026-10-25T00:00:00Z","",own,opponent,true);
    }
    @Test public void firstLoadNewMatchAndConcededGoalsAreSilent() {
        Football.GoalGate gate=new Football.GoalGate();
        assertEquals(0,gate.observe(match("a","LIVE",2,0)));
        assertEquals(0,gate.observe(match("a","LIVE",2,1)));
        assertEquals(0,gate.observe(match("b","LIVE",3,0)));
        assertEquals(1,gate.observe(match("b","LIVE",4,0)));
    }
    @Test public void scoreCorrectionsAndRepeatedSnapshotsDoNotRepeatAlerts() {
        Football.GoalGate gate=new Football.GoalGate();
        gate.observe(match("a","LIVE",0,0));
        assertEquals(1,gate.observe(match("a","LIVE",1,0)));
        assertEquals(0,gate.observe(match("a","LIVE",1,0)));
        assertEquals(0,gate.observe(match("a","LIVE",0,0)));
        assertEquals(0,gate.observe(match("a","LIVE",1,0)));
        assertEquals(1,gate.observe(match("a","LIVE",2,0)));
    }
    @Test public void finalWhistleGoalIsDetectedButOldFinishedResultsAreNot() {
        Football.GoalGate gate=new Football.GoalGate();
        gate.observe(match("a","LIVE",1,0));
        assertEquals(1,gate.observe(match("a","RESULT",2,0)));
        assertEquals(0,gate.observe(match("a","RESULT",2,0)));
        assertEquals(0,gate.observe(match("b","RESULT",4,0)));
    }
    @Test public void publicHtmlDataIsReadWithoutScriptsBeingExecuted() throws Exception {
        assertEquals(7,Football.extract("<script id=\"__NEXT_DATA__\" type=\"application/json\">{\"x\":7}</script>").getInt("x"));
        try {Football.extract("<html>maintenance</html>");fail("Missing data must fail");}
        catch(java.io.IOException expected) { }
    }
}
