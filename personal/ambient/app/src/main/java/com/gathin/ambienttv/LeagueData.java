package com.gathin.ambienttv;

import org.json.*;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

/** League tables and leaderboards; provider identifiers stay internal to the data layer. */
final class LeagueData {
    private static final String[] NAMES={"LaLiga","Botola Pro"};
    private static void league(int league){if(league<0||league>1)throw new IllegalArgumentException("Compétition inconnue");}
    static JSONObject standings(int league)throws Exception{
        league(league);
        if(league==0){try{return espnStandings(new JSONObject(MatchFeed.get("https://site.web.api.espn.com/apis/v2/sports/soccer/esp.1/standings",2_000_000)));}catch(Exception ignored){}}
        return table(KoooraFeed.page(path(league,"جدول")),league,false,LocalDate.now(MatchFeed.zone()).getYear());
    }
    static JSONObject scorers(int league)throws Exception{
        league(league);return table(KoooraFeed.page(path(league,"أفضل-اللاعبين")),league,true,LocalDate.now(MatchFeed.zone()).getYear());
    }
    static JSONObject clubs(int league)throws Exception{
        league(league);
        if(league==0){try{return espnClubs(new JSONObject(MatchFeed.get("https://site.api.espn.com/apis/site/v2/sports/soccer/esp.1/teams?limit=100",2_000_000)));}catch(Exception ignored){}}
        JSONObject data=KoooraFeed.page(path(league,"جدول"));String season=active(data,league,LocalDate.now(MatchFeed.zone()).getYear());
        JSONArray clubs=new JSONArray(),names=new JSONArray(),tables=data.getJSONObject("standings").getJSONArray("tables");Set<String> seen=new HashSet<>();
        for(int t=0;t<tables.length();t++){JSONArray rankings=tables.getJSONObject(t).getJSONArray("rankings");for(int i=0;i<rankings.length();i++){
            JSONObject team=rankings.getJSONObject(i).getJSONObject("team");String id=team.getString("id");if(!seen.add(id))continue;
            String name=KoooraFeed.name(team);JSONObject image=team.optJSONObject("image");
            clubs.put(new JSONObject().put("id","kooora:"+id).put("name",name).put("logo",image==null?"":image.optString("url")));names.put(name);
        }}
        if(clubs.length()==0)throw new IOException("Clubs indisponibles");
        return result(new JSONArray().put(section(NAMES[league]+" · "+season,names))).put("clubs",clubs);
    }
    private static String path(int league,String page){return "كرة-القدم/مسابقة/"+KoooraFeed.SLUG[league]+"/"+page+"/"+KoooraFeed.COMP[league];}
    private static String active(JSONObject data,int league,int year)throws Exception{
        if(!KoooraFeed.COMP[league].equals(data.getJSONObject("competition").getString("id")))throw new IOException("Compétition incorrecte");
        JSONArray seasons=data.getJSONArray("seasons");String name="";
        for(int i=0;i<seasons.length();i++){JSONObject s=seasons.getJSONObject(i);if(s.optBoolean("active")){if(!name.isEmpty())throw new IOException("Saison ambiguë");name=s.getString("name");}}
        if(name.isEmpty()||!(name.contains(String.valueOf(year))||name.contains(String.valueOf(year-1))))throw new IOException("Saison actuelle indisponible");
        return name;
    }
    static JSONObject table(JSONObject data,int league,boolean scorers,int year)throws Exception{
        String season=active(data,league,year);JSONArray rows=new JSONArray(),sections=new JSONArray();
        if(scorers){
            JSONArray goals=data.getJSONObject("topPlayers").getJSONArray("goals");int rank=0,previous=-1;
            for(int i=0;i<goals.length()&&i<50;i++){
                JSONObject g=goals.getJSONObject(i);int count=g.getInt("value");if(count<0)throw new IOException("Total de buts incorrect");
                if(count!=previous)rank=i+1;previous=count;
                rows.put(rank+". "+player(g.getJSONObject("player").getString("name"))+" · "+KoooraFeed.name(g.getJSONObject("team"))+" · "+count+" buts");
            }
            sections.put(section("Buteurs · "+NAMES[league]+" · "+season,rows));
        }else{
            JSONArray tables=data.getJSONObject("standings").getJSONArray("tables");
            for(int t=0;t<tables.length();t++){
                JSONArray rankings=tables.getJSONObject(t).getJSONArray("rankings"),lines=new JSONArray();
                for(int i=0;i<rankings.length();i++){
                    JSONObject r=rankings.getJSONObject(i);if(r.getInt("position")<1||r.getInt("played")<0)throw new IOException("Classement incorrect");
                    String name=KoooraFeed.name(r.getJSONObject("team"));
                    lines.put(r.getInt("position")+". "+name+" · "+r.getInt("points")+" pts · "+r.getInt("played")+" J · "+r.getInt("win")+" V / "+r.getInt("draw")+" N / "+r.getInt("lose")+" D · "+r.getInt("goalsFor")+" BP / "+r.getInt("goalsAgainst")+" BC · Diff. "+signed(r.getInt("goalsDifference")));
                    JSONArray form=r.optJSONArray("form");if(form!=null&&form.length()>0){StringBuilder f=new StringBuilder();for(int k=0;k<Math.min(form.length(),5);k++){String w=form.getJSONObject(k).optString("wdl");f.append("WIN".equals(w)?"V ":"DRAW".equals(w)?"N ":"LOSE".equals(w)?"D ":"– ");}lines.put("    Forme : "+f.toString().trim());}
                }
                if(lines.length()>0){rows=lines;sections.put(section("Classement · "+NAMES[league]+" · "+season,lines));}
            }
        }
        if(rows.length()==0)throw new IOException(scorers?"Buteurs indisponibles pour cette saison":"Classement indisponible pour cette saison");
        return result(sections);
    }
    static JSONObject espnClubs(JSONObject data)throws Exception{
        JSONArray teams=data.getJSONArray("sports").getJSONObject(0).getJSONArray("leagues").getJSONObject(0).getJSONArray("teams"),clubs=new JSONArray(),rows=new JSONArray();
        for(int i=0;i<teams.length();i++){JSONObject t=teams.getJSONObject(i).getJSONObject("team");JSONArray logos=t.optJSONArray("logos");String name=t.getString("displayName");clubs.put(new JSONObject().put("id",t.getString("id")).put("name",name).put("logo",logos==null||logos.length()==0?"":logos.getJSONObject(0).optString("href")));rows.put(name);}
        if(clubs.length()==0)throw new IOException("Clubs indisponibles");return result(new JSONArray().put(section("Clubs · LaLiga",rows))).put("clubs",clubs);
    }
    static JSONObject espnStandings(JSONObject data)throws Exception{
        JSONArray sections=new JSONArray(),groups=data.optJSONArray("children");
        if(groups==null){groups=new JSONArray().put(data);}
        int count=0;
        for(int g=0;g<groups.length();g++){
            JSONObject group=groups.getJSONObject(g),stand=group.optJSONObject("standings");if(stand==null)continue;JSONArray entries=stand.optJSONArray("entries");if(entries==null)continue;
            JSONArray rows=new JSONArray();
            for(int i=0;i<entries.length();i++){
                JSONObject e=entries.getJSONObject(i);JSONArray stats=e.getJSONArray("stats");Map<String,String> values=new HashMap<>();
                for(int k=0;k<stats.length();k++){JSONObject s=stats.getJSONObject(k);values.put(s.optString("name"),s.optString("displayValue",s.optString("value","—")));}
                String rank=value(values,"rank");if("—".equals(rank))rank=String.valueOf(i+1);
                rows.put(rank+". "+e.getJSONObject("team").getString("displayName")+" · "+value(values,"points")+" pts · "+value(values,"gamesPlayed")+" J · "+value(values,"wins")+" V / "+value(values,"ties")+" N / "+value(values,"losses")+" D · "+value(values,"pointsFor")+" BP / "+value(values,"pointsAgainst")+" BC · Diff. "+value(values,"pointDifferential"));count++;
            }
            if(rows.length()>0)sections.put(section("Classement · LaLiga",rows));
        }
        if(count==0)throw new IOException("Classement indisponible");return result(sections);
    }
    private static String value(Map<String,String> values,String key){return values.containsKey(key)?values.get(key):"—";}
    private static String signed(int n){return n>0?"+"+n:String.valueOf(n);}
    private static JSONObject section(String title,JSONArray rows)throws Exception{return new JSONObject().put("title",title).put("rows",rows);}
    private static JSONObject result(JSONArray sections)throws Exception{return new JSONObject().put("sections",sections).put("checked",System.currentTimeMillis());}
    private static String player(String name){
        String[] from={"رافينيا","روبرت ليفاندوفسكي","كيليان مبابي","لامين يامال","فينيسيوس جونيور","أنطوان غريزمان","جوليان ألفاريز","جود بيلينغهام","فيران توريس","بيدري"};
        String[] to={"Raphinha","Robert Lewandowski","Kylian Mbappé","Lamine Yamal","Vinícius Júnior","Antoine Griezmann","Julián Álvarez","Jude Bellingham","Ferran Torres","Pedri"};
        for(int i=0;i<from.length;i++)if(from[i].equals(name))return to[i];return name;
    }
}
