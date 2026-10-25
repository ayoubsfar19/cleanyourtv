package com.gathin.ambienttv;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SportsDataTest {
    @Test public void zeroStatisticIsKeptAndMissingValuesOmitted()throws Exception{
        JSONObject root=new JSONObject("{\"boxscore\":{\"teams\":[{\"team\":{\"displayName\":\"Barcelona\"},\"statistics\":[{\"name\":\"redCards\",\"displayValue\":\"0\"},{\"name\":\"possessionPct\",\"displayValue\":\"61%\"},{\"name\":\"saves\"}]}]}}");
        String result=SportsData.parseMatch(root).toString();assertTrue(result.contains("Cartons rouges : 0"));assertTrue(result.contains("Possession : 61%"));assertFalse(result.contains("Arrêts :"));
    }
    @Test public void startersAndBenchStaySeparate()throws Exception{
        JSONObject root=new JSONObject("{\"rosters\":[{\"team\":{\"displayName\":\"Barcelona\"},\"formation\":\"4-3-3\",\"roster\":[{\"starter\":true,\"athlete\":{\"displayName\":\"Starter\"},\"jersey\":\"9\"},{\"starter\":false,\"athlete\":{\"displayName\":\"Bench\"}}]}]}");
        JSONArray sections=SportsData.parseMatch(root).getJSONArray("sections");assertEquals(2,sections.length());assertTrue(sections.getJSONObject(0).getString("title").startsWith("Titulaires"));assertTrue(sections.getJSONObject(0).getJSONArray("rows").toString().contains("Starter"));assertFalse(sections.getJSONObject(0).getJSONArray("rows").toString().contains("Bench"));assertTrue(sections.getJSONObject(1).getJSONArray("rows").toString().contains("Bench"));
    }
    @Test public void clubCanUseRosterEvenWhenProfileMissing()throws Exception{
        JSONObject roster=new JSONObject("{\"athletes\":[{\"items\":[{\"displayName\":\"Player One\",\"position\":{\"abbreviation\":\"GK\"},\"jersey\":\"1\"}]}]}");
        String result=SportsData.parseClub(new JSONObject(),roster,new JSONObject(),"Club").toString();assertTrue(result.contains("Player One"));assertTrue(result.contains("GK"));assertFalse(result.contains("Stade :"));
    }
    @Test public void repeatedEventsAreDeduplicated()throws Exception{
        JSONObject root=new JSONObject("{\"scoringPlays\":[{\"text\":\"Player scores\",\"clock\":{\"displayValue\":\"23′\"},\"type\":{\"text\":\"Goal\"}}],\"keyEvents\":[{\"text\":\"Player scores\",\"clock\":{\"displayValue\":\"23′\"},\"type\":{\"text\":\"Goal\"}}]}");
        JSONArray sections=SportsData.parseMatch(root).getJSONArray("sections");assertEquals(1,sections.getJSONObject(0).getJSONArray("rows").length());assertTrue(sections.toString().contains("But"));
    }
}
