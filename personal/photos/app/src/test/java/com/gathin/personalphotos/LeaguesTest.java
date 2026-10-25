package com.gathin.personalphotos;
import org.junit.Test;
import org.json.JSONObject;
import org.json.JSONArray;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class LeaguesTest {
    private JSONObject fixture(String name)throws Exception{
        try(java.io.InputStream in=getClass().getResourceAsStream("/"+name+".json")){
            assertNotNull(in);return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    @Test public void bothCompleteLeagueTablesSurviveNormalization()throws Exception{
        for(int league=0;league<2;league++){
            JSONObject result=Leagues.normalize(fixture((league==0?"esp":"mar")+"-standings"),league,false,123);
            JSONArray rows=result.getJSONArray("rows");
            assertEquals(league==0?20:16,rows.length());
            assertEquals("2026/2027",result.getString("season"));
            assertTrue(rows.getJSONObject(0).getString("name").startsWith("1. "));
            assertTrue(rows.getJSONObject(rows.length()-1).getString("name").startsWith(rows.length()+". "));
            assertEquals(123,result.getLong("checked"));
        }
    }
    @Test public void bothTopTenListsContainGoalTotalsAndTeamNames()throws Exception{
        for(int league=0;league<2;league++){
            JSONObject result=Leagues.normalize(fixture((league==0?"esp":"mar")+"-scorers"),league,true,123);
            JSONArray rows=result.getJSONArray("rows");assertEquals(10,rows.length());
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);
                assertFalse(row.getString("name").isEmpty());assertFalse(row.getString("details").isEmpty());
                assertTrue(row.getInt("value")>=0);
            }
        }
    }
    @Test public void tiedMoroccanLeadersShareTheSameRank()throws Exception{
        JSONArray rows=Leagues.normalize(fixture("mar-scorers"),1,true,0).getJSONArray("rows");
        assertEquals(rows.getJSONObject(0).getInt("value"),rows.getJSONObject(1).getInt("value"));
        assertEquals(rows.getJSONObject(0).getString("extra"),rows.getJSONObject(1).getString("extra"));
    }
    @Test public void wrongCompetitionAndUnknownSeasonAreRejected()throws Exception{
        try{Leagues.normalize(fixture("esp-standings"),1,false,0);fail("Wrong league");}
        catch(java.io.IOException expected){}
        JSONObject page=fixture("esp-standings");
        page.getJSONObject("props").getJSONObject("pageProps").getJSONObject("data").getJSONArray("seasons").getJSONObject(0).put("active",false);
        try{Leagues.normalize(page,0,false,0);fail("Unknown season");}
        catch(java.io.IOException expected){}
    }
}
