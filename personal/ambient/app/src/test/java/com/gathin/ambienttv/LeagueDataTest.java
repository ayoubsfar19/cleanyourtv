package com.gathin.ambienttv;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class LeagueDataTest {
    private JSONObject data()throws Exception{
        return new JSONObject().put("competition",new JSONObject().put("id",KoooraFeed.COMP[0]))
            .put("seasons",new JSONArray().put(new JSONObject().put("name","2026/2027").put("active",true)));
    }
    private JSONObject scorer(String name,int goals)throws Exception{
        return new JSONObject().put("value",goals).put("player",new JSONObject().put("name",name))
            .put("team",new JSONObject().put("id","unknown").put("name","Test Club"));
    }
    @Test public void scorersShareRanksAndDoNotExposeProviders()throws Exception{
        JSONObject d=data().put("topPlayers",new JSONObject().put("goals",new JSONArray().put(scorer("A",8)).put(scorer("B",8)).put(scorer("C",5))));
        JSONArray rows=LeagueData.table(d,0,true,2026).getJSONArray("sections").getJSONObject(0).getJSONArray("rows");
        assertTrue(rows.getString(0).startsWith("1."));assertTrue(rows.getString(1).startsWith("1."));assertTrue(rows.getString(2).startsWith("3."));
        assertFalse(rows.toString().contains("Kooora"));assertFalse(rows.toString().contains("ESPN"));
    }
    @Test(expected=java.io.IOException.class) public void wrongCompetitionIsRejected()throws Exception{
        JSONObject d=data();d.getJSONObject("competition").put("id","another");LeagueData.table(d,0,true,2026);
    }
    @Test(expected=java.io.IOException.class) public void archivedSeasonIsRejected()throws Exception{
        JSONObject d=data();d.getJSONArray("seasons").getJSONObject(0).put("name","2023/2024");LeagueData.table(d,0,true,2026);
    }
    @Test(expected=java.io.IOException.class) public void missingActiveSeasonIsRejected()throws Exception{
        JSONObject d=data();d.getJSONArray("seasons").getJSONObject(0).put("active",false);LeagueData.table(d,0,true,2026);
    }
    @Test(expected=java.io.IOException.class) public void negativeScorerGoalsAreRejected()throws Exception{
        LeagueData.table(data().put("topPlayers",new JSONObject().put("goals",new JSONArray().put(scorer("A",-1)))),0,true,2026);
    }
    @Test(expected=java.io.IOException.class) public void emptyStandingsAreNotReturnedAsData()throws Exception{
        LeagueData.espnStandings(new JSONObject().put("children",new JSONArray()));
    }
    @Test public void clubsRetainCanonicalIdsForDrilldown()throws Exception{
        JSONObject team=new JSONObject().put("id","83").put("displayName","Barcelona");
        JSONObject input=new JSONObject().put("sports",new JSONArray().put(new JSONObject().put("leagues",new JSONArray().put(new JSONObject().put("teams",new JSONArray().put(new JSONObject().put("team",team)))))));
        assertEquals("83",LeagueData.espnClubs(input).getJSONArray("clubs").getJSONObject(0).getString("id"));
    }
}
