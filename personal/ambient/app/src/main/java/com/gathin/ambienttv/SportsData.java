package com.gathin.ambienttv;

import org.json.*;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** On-demand details only. Missing values are omitted rather than invented. */
final class SportsData {
    private static final String BASE="https://site.api.espn.com/apis/site/v2/sports/soccer/esp.1/";
    static JSONObject match(MatchFeed.Match m) throws Exception {
        if(m.id.startsWith("kooora:"))return KoooraFeed.match(m);
        return parseMatch(new JSONObject(MatchFeed.get(BASE+"summary?event="+m.id,3_000_000)));
    }
    static JSONObject club(String id,String name) throws Exception {
        if(id.startsWith("kooora:"))return KoooraFeed.club(id,name);
        if(!id.matches("[0-9]+"))throw new IOException("Invalid club");
        JSONObject profile=optional("teams/"+id), roster=optional("teams/"+id+"/roster"), schedule=optional("teams/"+id+"/schedule");
        JSONObject result=parseClub(profile,roster,schedule,name);
        if(result.getJSONArray("sections").length()==0)throw new IOException("Club details unavailable");
        return result;
    }
    private static JSONObject optional(String path){try{return new JSONObject(MatchFeed.get(BASE+path,2_000_000));}catch(Exception e){return new JSONObject();}}
    static JSONObject parseMatch(JSONObject data) throws JSONException {
        JSONArray sections=new JSONArray();
        JSONObject info=data.optJSONObject("gameInfo");JSONArray facts=new JSONArray();
        if(info!=null){
            JSONObject venue=info.optJSONObject("venue");if(venue!=null){add(facts,"Stade",venue.optString("fullName"));JSONObject address=venue.optJSONObject("address");if(address!=null)add(facts,"Ville",address.optString("city"));}
            if(info.optInt("attendance")>0)add(facts,"Spectateurs",String.valueOf(info.optInt("attendance")));
            JSONArray officials=info.optJSONArray("officials");if(officials!=null)for(int i=0;i<officials.length()&&i<6;i++){JSONObject o=officials.optJSONObject(i);if(o!=null)add(facts,"Arbitre",o.optString("displayName",o.optString("fullName")));}
        }
        section(sections,"Informations du match",facts);
        JSONObject box=data.optJSONObject("boxscore");JSONArray teams=box==null?null:box.optJSONArray("teams");
        if(teams!=null)for(int i=0;i<teams.length()&&i<2;i++){
            JSONObject team=teams.optJSONObject(i);if(team==null)continue;JSONArray rows=new JSONArray(),stats=team.optJSONArray("statistics");
            if(stats!=null)for(int j=0;j<stats.length()&&j<60;j++){JSONObject stat=stats.optJSONObject(j);if(stat==null)continue;String value=stat.optString("displayValue");if(value.isEmpty()&&stat.has("value"))value=String.valueOf(stat.opt("value"));add(rows,statLabel(stat.optString("name"),stat.optString("label",stat.optString("displayName"))),value);}
            section(sections,"Statistiques · "+teamName(team),rows);
        }
        JSONArray moments=new JSONArray();Set<String> seen=new HashSet<>();
        for(String key:new String[]{"scoringPlays","keyEvents","plays"}){
            JSONArray events=data.optJSONArray(key);if(events==null)continue;
            for(int i=0;i<events.length()&&moments.length()<100;i++){
                JSONObject event=events.optJSONObject(i);if(event==null)continue;String text=event.optString("text",event.optString("shortText"));
                if(text.isEmpty()){JSONObject athlete=event.optJSONObject("athlete");if(athlete!=null)text=athlete.optString("displayName");}
                JSONObject clock=event.optJSONObject("clock"),type=event.optJSONObject("type");String minute=clock==null?"":clock.optString("displayValue",clock.optString("displayClock"));
                String kind=type==null?"":eventLabel(type.optString("text",type.optString("name")));
                String row=(minute.isEmpty()?"":minute+" · ")+(kind.isEmpty()?"":kind+" · ")+text;
                if(!text.isEmpty()&&seen.add(row))moments.put(row);
            }
        }
        section(sections,"Faits de jeu",moments);
        JSONArray rosters=data.optJSONArray("rosters");
        if(rosters!=null)for(int i=0;i<rosters.length()&&i<2;i++){
            JSONObject r=rosters.optJSONObject(i);if(r==null)continue;JSONArray starters=new JSONArray(),bench=new JSONArray();add(starters,"Formation",r.optString("formation"));
            JSONArray players=r.optJSONArray("roster");if(players==null)players=r.optJSONArray("athletes");
            if(players!=null)for(int j=0;j<players.length()&&j<40;j++){JSONObject p=players.optJSONObject(j);if(p==null)continue;String label=player(p);if(!label.isEmpty())(p.optBoolean("starter")?starters:bench).put(label);}
            section(sections,"Titulaires · "+teamName(r),starters);section(sections,"Remplaçants · "+teamName(r),bench);
        }
        if(sections.length()==0)section(sections,"Détails",new JSONArray().put("Les détails ne sont pas encore disponibles pour ce match."));
        return result(sections);
    }
    static JSONObject parseClub(JSONObject profile,JSONObject roster,JSONObject schedule,String fallback) throws JSONException {
        JSONArray sections=new JSONArray();JSONObject team=profile.optJSONObject("team");JSONArray facts=new JSONArray();
        if(team!=null){add(facts,"Club",team.optString("displayName",fallback));add(facts,"Surnom",team.optString("nickname"));add(facts,"Ville",team.optString("location"));JSONObject venue=team.optJSONObject("venue");if(venue!=null){add(facts,"Stade",venue.optString("fullName"));if(venue.optInt("capacity")>0)add(facts,"Capacité",String.valueOf(venue.optInt("capacity")));}
            JSONObject record=team.optJSONObject("record");JSONArray items=record==null?null:record.optJSONArray("items");if(items!=null)for(int i=0;i<items.length()&&i<6;i++){JSONObject item=items.optJSONObject(i);if(item!=null)add(facts,item.optString("description","Bilan"),item.optString("summary"));}
        }
        section(sections,"Le club",facts);
        JSONArray squad=new JSONArray();JSONArray athletes=roster.optJSONArray("athletes");
        if(athletes!=null)for(int i=0;i<athletes.length()&&squad.length()<60;i++){JSONObject p=athletes.optJSONObject(i);if(p==null)continue;JSONArray items=p.optJSONArray("items");if(items!=null){for(int j=0;j<items.length()&&squad.length()<60;j++){JSONObject a=items.optJSONObject(j);if(a!=null){String line=player(a);if(!line.isEmpty())squad.put(line);}}}else{String line=player(p);if(!line.isEmpty())squad.put(line);}}
        JSONArray coaches=roster.optJSONArray("coach");if(coaches==null)coaches=roster.optJSONArray("coaches");if(coaches!=null)for(int i=0;i<coaches.length()&&i<4;i++){JSONObject coach=coaches.optJSONObject(i);if(coach!=null)add(squad,"Entraîneur",coach.optString("displayName",coach.optString("fullName")));}
        section(sections,"Effectif",squad);
        JSONArray recent=new JSONArray(),upcoming=new JSONArray();
        try{List<MatchFeed.Match> matches=MatchFeed.parse(schedule.toString());int begin=Math.max(0,matches.size()-30);for(int i=begin;i<matches.size();i++){MatchFeed.Match m=matches.get(i);String when=m.start;try{when=OffsetDateTime.parse(m.start).atZoneSameInstant(MatchFeed.zone()).format(DateTimeFormatter.ofPattern("dd/MM HH:mm",Locale.FRANCE));}catch(Exception ignored){}String line=when+" · "+m.home+" "+m.score()+" "+m.away;if(m.finished())recent.put(line);else upcoming.put(line);}}catch(Exception ignored){}
        section(sections,"Résultats récents",recent);section(sections,"Calendrier",upcoming);return result(sections);
    }
    private static String player(JSONObject entry){JSONObject a=entry.optJSONObject("athlete");if(a==null)a=entry;String name=a.optString("displayName",a.optString("fullName"));if(name.isEmpty())return "";String jersey=entry.optString("jersey",a.optString("jersey"));JSONObject pos=entry.optJSONObject("position");if(pos==null)pos=a.optJSONObject("position");String position=pos==null?"":pos.optString("abbreviation",pos.optString("displayName"));return(jersey.isEmpty()?"":"#"+jersey+" · ")+name+(position.isEmpty()?"":" · "+position);}
    private static String teamName(JSONObject entry){JSONObject team=entry.optJSONObject("team");return team==null?"Équipe":team.optString("displayName",team.optString("name","Équipe"));}
    private static void add(JSONArray rows,String label,String value){if(value!=null&&!value.isEmpty()&&!"null".equals(value))rows.put(label+" : "+value);}
    private static void section(JSONArray sections,String title,JSONArray rows)throws JSONException{if(rows.length()>0)sections.put(new JSONObject().put("title",title).put("rows",rows));}
    private static JSONObject result(JSONArray sections)throws JSONException{return new JSONObject().put("sections",sections).put("checked",System.currentTimeMillis());}
    private static String eventLabel(String s){String n=s.toLowerCase(Locale.ROOT);if(n.contains("yellow"))return "Carton jaune";if(n.contains("red"))return "Carton rouge";if(n.contains("substitution"))return "Remplacement";if(n.contains("goal"))return "But";if(n.contains("penalty"))return "Penalty";return s;}
    private static String statLabel(String name,String fallback){switch(name){case"possessionPct":return"Possession";case"totalShots":return"Tirs";case"shotsOnTarget":return"Tirs cadrés";case"shotsOffTarget":return"Tirs non cadrés";case"blockedShots":return"Tirs contrés";case"foulsCommitted":return"Fautes commises";case"yellowCards":return"Cartons jaunes";case"redCards":return"Cartons rouges";case"offsides":return"Hors-jeu";case"cornerKicks":return"Corners";case"saves":return"Arrêts";case"totalPasses":return"Passes";case"accuratePasses":return"Passes réussies";case"passPct":return"Précision des passes";case"tackles":return"Tacles";case"interceptions":return"Interceptions";case"totalCrosses":return"Centres";case"accurateCrosses":return"Centres réussis";case"wonTacklePct":return"Tacles réussis";case"totalLongBalls":return"Passes longues";case"accurateLongBalls":return"Passes longues réussies";case"totalClearance":return"Dégagements";case"goalAssists":return"Passes décisives";case"goals":return"Buts";case"shotsBlocked":return"Tirs bloqués";default:return fallback.isEmpty()?name:fallback;}}
}
