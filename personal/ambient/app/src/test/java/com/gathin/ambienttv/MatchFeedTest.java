package com.gathin.ambienttv;
import org.junit.Test;
import static org.junit.Assert.*;
import java.time.*;
import java.util.*;

public class MatchFeedTest {
    private MatchFeed.Match m(String state,int home,int away){
        MatchFeed.Match m=new MatchFeed.Match();m.id="test";m.home="Barcelona";m.away="Valencia";m.hs=home;m.as=away;m.state=state;m.status="STATUS_SCHEDULED";m.clock="52′";m.start="2026-10-25T13:00Z";return m;
    }
    private final long now=Instant.parse("2026-10-25T14:00:00Z").toEpochMilli();
    @Test public void espnDatesWithoutSecondsWork(){assertEquals(OffsetDateTime.parse("2026-10-25T13:00Z").atZoneSameInstant(MatchFeed.ZONE).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),m("pre",0,0).label());}
    @Test public void initialLiveScoreIsNotGoal(){assertNull(new MatchFeed.GoalGate().observe(m("in",2,0),now));}
    @Test public void goalsForEitherTeamAppearOnce(){MatchFeed.GoalGate g=new MatchFeed.GoalGate();g.observe(m("in",0,0),now);assertNotNull(g.observe(m("in",1,0),now));assertNull(g.observe(m("in",1,0),now));assertTrue(g.observe(m("in",1,1),now).startsWith("BUT !  ·  Valencia"));}
    @Test public void varCorrectionDoesNotDuplicate(){MatchFeed.GoalGate g=new MatchFeed.GoalGate();g.observe(m("in",0,0),now);assertNotNull(g.observe(m("in",1,0),now));assertNull(g.observe(m("in",0,0),now));assertNull(g.observe(m("in",1,0),now));assertNotNull(g.observe(m("in",2,0),now));}
    @Test public void archivedResultsNeverAlert(){MatchFeed.GoalGate g=new MatchFeed.GoalGate();g.observe(m("post",0,0),now);assertNull(g.observe(m("post",3,0),now));}
    @Test public void staleLiveFlagsNeverAlert(){MatchFeed.GoalGate g=new MatchFeed.GoalGate();g.observe(m("in",0,0),now);assertNull(g.observe(m("in",2,0),now+24*3600_000L));}
    @Test public void finalWhistleGoalStillAlerts(){MatchFeed.GoalGate g=new MatchFeed.GoalGate();g.observe(m("in",1,0),now);assertNotNull(g.observe(m("post",2,0),now));assertNull(g.observe(m("post",2,0),now));}
    @Test public void homeAwayOrderIsReadFromFields() throws Exception {
        String raw="{\"events\":[{\"id\":\"42\",\"date\":\"2026-10-25T13:00Z\",\"competitions\":[{\"status\":{\"type\":{\"state\":\"in\"}},\"competitors\":[{\"homeAway\":\"away\",\"score\":\"2\",\"team\":{\"displayName\":\"Away\"}},{\"homeAway\":\"home\",\"score\":\"1\",\"team\":{\"displayName\":\"Home\"}}]}]}]}";
        List<MatchFeed.Match> matches=MatchFeed.parse(raw);assertEquals(1,matches.size());assertEquals("Home",matches.get(0).home);assertEquals("1  –  2",matches.get(0).score());
    }
    @Test public void malformedEventDoesNotHideRest() throws Exception {assertTrue(MatchFeed.parse("{\"events\":[{}]}").isEmpty());}
}
