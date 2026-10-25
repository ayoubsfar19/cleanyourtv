package com.gathin.ambienttv;

import org.json.*;
import java.net.*;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Public ESPN scoreboard, read-only; no keys, accounts or personal photos sent. */
final class MatchFeed {
    /** Read the current device zone each time; never add a fixed hour or pin a country zone. */
    static ZoneId zone(){ return java.util.TimeZone.getDefault().toZoneId(); }
    static final class Match {
        String id, home, away, homeId, awayId, detailPath, homeLogo, awayLogo, state, clock, start, status;
        int hs, as;
        boolean live(){ return "in".equals(state); }
        boolean finished(){ return "post".equals(state); }
        String score(){return live()||finished()?hs+"  –  "+as:"–";}
        String label(){
            if(live()) return "EN DIRECT  ·  "+clock;
            if(finished())return "TERMINÉ";
            if(status.contains("POSTPON"))return "REPORTÉ";
            if(status.contains("CANCEL"))return "ANNULÉ";
            try{return OffsetDateTime.parse(start).toInstant().atZone(zone()).format(DateTimeFormatter.ofPattern("HH:mm"));}
            catch(Exception e){return "À venir";}
        }
    }
    static List<Match> parse(String json) throws Exception {
        JSONArray events=new JSONObject(json).getJSONArray("events");
        List<Match> result=new ArrayList<>();
        for(int i=0;i<events.length();i++){
            try{
                JSONObject e=events.getJSONObject(i), c=e.getJSONArray("competitions").getJSONObject(0);
                JSONObject st=c.getJSONObject("status"), type=st.getJSONObject("type");
                JSONArray teams=c.getJSONArray("competitors");
                JSONObject h=null,a=null;
                for(int k=0;k<teams.length();k++){
                    JSONObject t=teams.getJSONObject(k);
                    if("home".equals(t.optString("homeAway")))h=t;
                    if("away".equals(t.optString("homeAway")))a=t;
                }
                if(h==null||a==null)continue;
                Match m=new Match();m.id=e.getString("id");m.start=e.getString("date");
                m.state=type.optString("state");m.status=type.optString("name");m.clock=st.optString("displayClock","");
                m.homeId=h.getJSONObject("team").optString("id");m.awayId=a.getJSONObject("team").optString("id");
                m.detailPath=e.optString("detailPath","");
                m.home=h.getJSONObject("team").getString("displayName");m.away=a.getJSONObject("team").getString("displayName");
                m.homeLogo=h.getJSONObject("team").optString("logo");m.awayLogo=a.getJSONObject("team").optString("logo");
                if((m.live()||m.finished())&&(h.isNull("score")||a.isNull("score")))continue;
                m.hs=Integer.parseInt(h.optString("score","0"));m.as=Integer.parseInt(a.optString("score","0"));
                if(m.hs<0||m.as<0)continue;
                result.add(m);
            }catch(JSONException|NumberFormatException ignored){/* One malformed event must not hide the others. */}
        }
        result.sort(Comparator.comparing(m->m.start));return result;
    }
    static String fetch(LocalDate date) throws Exception { return fetch(date,"esp.1"); }
    static String fetch(LocalDate date,String league) throws Exception {
        if ("mor.1".equals(league)) return KoooraFeed.scoreboard(date,1);
        if (!"esp.1".equals(league)) throw new IOException("Compétition inconnue");
        try {
            String raw=get("https://site.api.espn.com/apis/site/v2/sports/soccer/esp.1/scoreboard?dates="+date.format(DateTimeFormatter.BASIC_ISO_DATE),2_000_000);
            new JSONObject(raw).getJSONArray("events");
            return raw;
        } catch (IOException | JSONException unavailable) {
            return KoooraFeed.scoreboard(date,0);
        }
    }
    static String get(String url,int max) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(8000);c.setReadTimeout(10000);c.setRequestProperty("User-Agent","AmbientTV/2.0");
        try{
            if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[8192];int n;
                while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("Response too large");out.write(b,0,n);}
                return out.toString("UTF-8");
            }
        }finally{c.disconnect();}
    }
    static final class GoalGate {
        private final Map<String,int[]> seen=new HashMap<>();
        String observe(Match m,long now){
            int[] before=seen.get(m.id);
            seen.put(m.id,new int[]{m.hs,m.as,m.live()?1:0});
            if(before==null||before[2]!=1||(!m.live()&&!m.finished()))return null;
            try{long age=now-OffsetDateTime.parse(m.start).toInstant().toEpochMilli();if(age<0||age>8*3600_000L)return null;}
            catch(Exception e){return null;}
            // Keep the high-water marks when VAR reduces a score: a restored score isn't a second goal.
            int dh=m.hs-before[0], da=m.as-before[1];
            seen.put(m.id,new int[]{Math.max(before[0],m.hs),Math.max(before[1],m.as),m.live()?1:0});
            if(dh<=0&&da<=0)return null;
            return "BUT !  ·  "+(dh>0&&da>0?m.home+" / "+m.away:dh>0?m.home:m.away)+"\n"+m.home+"  "+m.score()+"  "+m.away;
        }
    }
}
