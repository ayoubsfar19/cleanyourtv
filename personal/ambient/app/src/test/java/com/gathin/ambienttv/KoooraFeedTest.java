package com.gathin.ambienttv;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

public class KoooraFeedTest {
    private JSONObject fixture(String name)throws Exception{
        try(InputStream in=getClass().getResourceAsStream("/"+name)){
            assertNotNull("Missing fixture "+name,in);return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    private String allRows(JSONObject doc)throws Exception{
        StringBuilder out=new StringBuilder();JSONArray sections=doc.getJSONArray("sections");
        for(int i=0;i<sections.length();i++){JSONObject s=sections.getJSONObject(i);out.append(s.getString("title")).append('\n');JSONArray r=s.getJSONArray("rows");for(int k=0;k<r.length();k++)out.append(r.getString(k)).append('\n');}
        return out.toString();
    }
    @Test public void currentRoundEventsPreserveIndependentIdentities()throws Exception{
        TimeZone old=TimeZone.getDefault();try{TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            JSONObject raw=KoooraFeed.scoreboardData(fixture("kooora-round.json"),LocalDate.of(2026,10,24),1);
            List<MatchFeed.Match> matches=MatchFeed.parse(raw.toString());assertEquals(3,matches.size());MatchFeed.Match m=matches.get(0);
            assertEquals("kooora:R8LTXBIq_lNh0omX1ci1o",m.id);assertTrue(m.homeId.startsWith("kooora:"));assertTrue(m.awayId.startsWith("kooora:"));assertEquals("Amal Tiznit",m.home);assertEquals("COD Meknès",m.away);assertEquals("1  –  2",m.score());assertTrue(m.finished());assertTrue(m.detailPath.startsWith("كرة-القدم/مباراة/"));
        }finally{TimeZone.setDefault(old);}
    }
    @Test(expected=IOException.class) public void wrongCompetitionIsRefused()throws Exception{
        JSONObject d=fixture("kooora-round.json");d.getJSONObject("competition").put("id",KoooraFeed.COMP[0]);KoooraFeed.scoreboardData(d,LocalDate.of(2026,10,24),1);
    }
    @Test(expected=IOException.class) public void unknownArchiveDayIsNotAnEmptySchedule()throws Exception{
        KoooraFeed.scoreboardData(fixture("kooora-round.json"),LocalDate.of(2020,1,1),1);
    }
    @Test(expected=IOException.class) public void unknownFutureDayIsNotAnEmptySchedule()throws Exception{
        KoooraFeed.scoreboardData(fixture("kooora-round.json"),LocalDate.of(2030,1,1),1);
    }
    @Test public void dayWithoutFixturesWithinKnownRoundIsEmpty()throws Exception{
        TimeZone old=TimeZone.getDefault();try{TimeZone.setDefault(TimeZone.getTimeZone("UTC"));assertEquals(0,KoooraFeed.scoreboardData(fixture("kooora-round.json"),LocalDate.of(2026,10,26),1).getJSONArray("events").length());}finally{TimeZone.setDefault(old);}
    }
    @Test public void fixtureDaysFollowTelevisionZoneAcrossMidnight()throws Exception{
        TimeZone old=TimeZone.getDefault();try{TimeZone.setDefault(TimeZone.getTimeZone("GMT+10:00"));
            List<MatchFeed.Match> matches=MatchFeed.parse(KoooraFeed.scoreboardData(fixture("kooora-round.json"),LocalDate.of(2026,10,25),1).toString());
            assertEquals(3,matches.size());assertEquals("kooora:R8LTXBIq_lNh0omX1ci1o",matches.get(0).id);
        }finally{TimeZone.setDefault(old);}
    }
    @Test(expected=IOException.class) public void detailWithDifferentMatchIdentityIsRejected()throws Exception{
        KoooraFeed.matchData(fixture("kooora-match.json"),"some-other-match");
    }
    @Test public void actualDetailsContainStatisticsChronologyAndConfirmedTeams()throws Exception{
        JSONObject input=fixture("kooora-match.json");String id=input.getJSONObject("match").getString("id");String rows=allRows(KoooraFeed.matchData(input,id));
        assertTrue(rows.contains("Possession (%) : 53  |  47"));assertTrue(rows.contains("Tirs cadrés : 8  |  3"));assertTrue(rows.contains("Cartons jaunes : 1  |  1"));assertTrue(rows.contains("Titulaires · Amal Tiznit"));assertTrue(rows.contains("22 · Y. Faqir"));assertTrue(rows.indexOf("16′")<rows.indexOf("82′"));assertTrue(rows.indexOf("82′")<rows.indexOf("85′"));assertFalse(rows.toLowerCase(Locale.ROOT).contains("kooora"));
    }
    @Test public void livePeriodAndScoreArePreserved()throws Exception{
        TimeZone old=TimeZone.getDefault();try{TimeZone.setDefault(TimeZone.getTimeZone("UTC"));JSONObject d=fixture("kooora-round.json"),m=d.getJSONArray("gamesets").getJSONObject(0).getJSONArray("matches").getJSONObject(0);m.put("status","LIVE");m.put("period",new JSONObject().put("minute",63));
            MatchFeed.Match live=MatchFeed.parse(KoooraFeed.scoreboardData(d,LocalDate.of(2026,10,24),1).toString()).get(0);assertTrue(live.live());assertEquals("63′",live.clock);assertEquals("1  –  2",live.score());
        }finally{TimeZone.setDefault(old);}
    }
    @Test public void aMissingLiveScoreIsNeverInventedAsZeroZero()throws Exception{
        TimeZone old=TimeZone.getDefault();try{TimeZone.setDefault(TimeZone.getTimeZone("UTC"));JSONObject d=fixture("kooora-round.json"),m=d.getJSONArray("gamesets").getJSONObject(0).getJSONArray("matches").getJSONObject(0);String id=m.getString("id");m.put("status","LIVE");m.put("score",JSONObject.NULL);
            List<MatchFeed.Match> parsed=MatchFeed.parse(KoooraFeed.scoreboardData(d,LocalDate.of(2026,10,24),1).toString());
            for(MatchFeed.Match item:parsed)assertNotEquals("kooora:"+id,item.id);
        }finally{TimeZone.setDefault(old);}
    }
}
