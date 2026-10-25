package com.gathin.ambienttv;

import org.json.*;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/** Independent public football provider. Source identity is kept internally; never splice scores across providers. */
final class KoooraFeed {
    static final String[] COMP={"34pl8szyvrbwcmfkuocjm3r6t","1eruend45vd20g9hbrpiggs5u"};
    static final String[] SLUG={"الدوري-الإسباني","الدوري-المغربي-الممتاز"};
    private static final Pattern DATA=Pattern.compile("<script[^>]*\\bid=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>",Pattern.DOTALL);
    static JSONObject data(JSONObject page) throws Exception{return page.getJSONObject("props").getJSONObject("pageProps").getJSONObject("data");}
    static JSONObject extract(String html) throws Exception {Matcher m=DATA.matcher(html);if(!m.find())throw new IOException("Données indisponibles");return data(new JSONObject(m.group(1)));}
    static JSONObject page(String path) throws Exception {
        String encoded=URLEncoder.encode(path,"UTF-8").replace("+","%20").replace("%2F","/");
        return extract(MatchFeed.get("https://www.kooora.com/"+encoded,3_000_000));
    }
    static String scoreboard(LocalDate date,int league) throws Exception {
        return scoreboardData(page("كرة-القدم/مسابقة/"+SLUG[league]+"/مباريات/"+COMP[league]),date,league).toString();
    }
    static JSONObject scoreboardData(JSONObject d,LocalDate date,int league) throws Exception {
        if(!COMP[league].equals(d.getJSONObject("competition").getString("id")))throw new IOException("Compétition incorrecte");
        JSONArray games=d.getJSONArray("gamesets"),events=new JSONArray();LocalDate first=null,last=null;boolean invalidScores=false;
        for(int i=0;i<games.length();i++){
            JSONArray ms=games.getJSONObject(i).optJSONArray("matches");if(ms==null)continue;
            for(int j=0;j<ms.length();j++){
                JSONObject m=ms.getJSONObject(j),comp=m.optJSONObject("competition");
                if(comp!=null&&!COMP[league].equals(comp.optString("id")))continue;
                OffsetDateTime at;try{at=OffsetDateTime.parse(m.getString("startDate"));}catch(Exception bad){continue;}
                LocalDate day=at.atZoneSameInstant(MatchFeed.zone()).toLocalDate();
                if(first==null||day.isBefore(first))first=day;if(last==null||day.isAfter(last))last=day;
                if(!date.equals(day))continue;
                JSONObject a=m.getJSONObject("teamA"),b=m.getJSONObject("teamB"),score=m.optJSONObject("score"),period=m.optJSONObject("period");
                String status=m.optString("status"),state="LIVE".equals(status)?"in":"RESULT".equals(status)?"post":"pre";
                if(!"pre".equals(state)&&(score==null||score.isNull("teamA")||score.isNull("teamB")||score.optInt("teamA",-1)<0||score.optInt("teamB",-1)<0)){invalidScores=true;continue;}
                JSONObject type=new JSONObject().put("state",state).put("name",status.contains("POSTPON")?"STATUS_POSTPONED":status.contains("CANCEL")?"STATUS_CANCELED":"STATUS_"+status);
                String minute=period==null?"":period.optInt("minute",0)+"′";
                JSONObject st=new JSONObject().put("type",type).put("displayClock",minute);
                JSONArray competitors=new JSONArray().put(competitor(a,"home",score,"teamA")).put(competitor(b,"away",score,"teamB"));
                JSONObject link=m.optJSONObject("link");String slug=link==null?"match":link.optString("slug","match").replace("-ضد-","-v-");
                events.put(new JSONObject().put("id","kooora:"+m.getString("id")).put("date",at.toString())
                        .put("detailPath","كرة-القدم/مباراة/"+slug+"/"+m.getString("id"))
                        .put("competitions",new JSONArray().put(new JSONObject().put("status",st).put("competitors",competitors))));
            }
        }
        // This public page covers the current round only. Never report an unknown archived day as an empty schedule.
        if(first==null||date.isBefore(first)||date.isAfter(last))throw new IOException("Programme indisponible pour cette date");
        if(events.length()==0&&invalidScores)throw new IOException("Scores indisponibles pour cette date");
        return new JSONObject().put("events",events);
    }
    private static JSONObject competitor(JSONObject team,String side,JSONObject score,String key) throws Exception {
        JSONObject image=team.optJSONObject("image");
        return new JSONObject().put("homeAway",side).put("score",score==null?"0":String.valueOf(score.optInt(key,0)))
                .put("team",new JSONObject().put("id","kooora:"+team.getString("id")).put("displayName",name(team))
                        .put("logo",image==null?"":image.optString("url","")));
    }
    static String name(JSONObject t){
        String id=t.optString("id"),n=t.optString("name",t.optString("longName","Équipe"));
        String[] ids={"agh9ifb2mw3ivjusgedj7c3fe","3kq9cckrnlogidldtdie2fkbl","4ku8o6uf87yd8iecdalipo6wd","ah8dala7suqqkj04n2l8xz4zd","3l9kyuwcn5wgjz5b1e3qrazqz","45md2vgytmsj2y0rat0sur1f6","x0mbz2vi8eyejsx1jc729svv"};
        String[] names={"FC Barcelona","Real Madrid","Atlético Madrid","Real Betis","Raja Casablanca","Wydad Casablanca","AS FAR"};
        for(int i=0;i<ids.length;i++)if(ids[i].equals(id))return names[i];
        String[] ar={"أمل تيزنيت","النادي المكناسي","المغرب الفاسي","الفتح الرباطي","إتحاد طنجة","اتحاد طنجة","نهضة بركان","نهضة الزمامرة","الدفاع الحسني الجديدي","حسنية أكادير","أولمبيك آسفي","أولمبيك الدشيرة","إتحاد يعقوب المنصور","الكوكب المراكشي"};
        String[] fr={"Amal Tiznit","COD Meknès","MAS Fès","FUS Rabat","Ittihad Tanger","Ittihad Tanger","RS Berkane","Renaissance Zemamra","DH El Jadida","Hassania Agadir","Olympique Safi","Olympique Dcheira","Union Yacoub El Mansour","Kawkab Marrakech"};
        for(int i=0;i<ar.length;i++)if(ar[i].equals(n))return fr[i];return n;
    }
    private static void section(JSONArray sections,String title,JSONArray rows)throws Exception{if(rows.length()>0)sections.put(new JSONObject().put("title",title).put("rows",rows));}
    static JSONObject match(MatchFeed.Match requested)throws Exception{
        if(requested.detailPath==null||!requested.detailPath.startsWith("كرة-القدم/مباراة/"))throw new IOException("Fiche du match indisponible");
        return matchData(page(requested.detailPath),requested.id.substring(7));
    }
    static JSONObject matchData(JSONObject data,String id)throws Exception{
        JSONObject m=data.getJSONObject("match");if(!id.equals(m.getString("id")))throw new IOException("Match incorrect");
        JSONArray sections=new JSONArray(),summary=new JSONArray();JSONObject a=m.getJSONObject("teamA"),b=m.getJSONObject("teamB");
        summary.put(name(a)+" / "+name(b));
        if(!m.isNull("venue"))summary.put("Stade : "+m.getJSONObject("venue").optString("name","—"));
        if(!m.isNull("startDate"))summary.put("Coup d’envoi : "+OffsetDateTime.parse(m.getString("startDate")).atZoneSameInstant(MatchFeed.zone()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
        JSONObject half=m.optJSONObject("halfTime");if(half!=null&&!half.isNull("teamA")&&!half.isNull("teamB"))summary.put("Mi-temps : "+half.optInt("teamA")+" – "+half.optInt("teamB"));
        section(sections,"Le match",summary);
        JSONObject stats=m.optJSONObject("stats");Set<String> used=new HashSet<>();
        if(stats!=null){String[] keys={"summary","attacking","passing","duels","defence","discipline"},titles={"Statistiques","Attaque","Passes","Duels","Défense","Discipline"};
            for(int i=0;i<keys.length;i++){JSONArray items=stats.optJSONArray(keys[i]),rows=new JSONArray();if(items==null)continue;
                for(int k=0;k<items.length();k++){JSONObject v=items.getJSONObject(k);String type=v.optString("type");if(!used.add(type)||v.isNull("teamA")||v.isNull("teamB"))continue;
                    rows.put(stat(type)+" : "+v.get("teamA")+"  |  "+v.get("teamB"));}
                section(sections,titles[i],rows);
            }
        }
        JSONArray events=m.optJSONArray("keyEvents"),timeline=new JSONArray();
        if(events!=null){List<JSONObject> ordered=new ArrayList<>();for(int i=0;i<events.length();i++)ordered.add(events.getJSONObject(i));ordered.sort(Comparator.comparingInt(v->v.optJSONObject("period")==null?0:v.optJSONObject("period").optInt("minute")));
            for(JSONObject e:ordered){JSONObject period=e.optJSONObject("period");String min=period==null?"":period.optInt("minute")+(period.optInt("extra")>0?"+"+period.optInt("extra"):"")+"′ · ";
                String type=e.optString("type"),label=type.contains("GOAL")?"But":type.contains("RED")?"Carton rouge":type.contains("YELLOW")?"Carton jaune":type.contains("SUB")?"Remplacement":type.replace('_',' ');
                JSONObject person=e.optJSONObject("scorer");if(person==null)person=e.optJSONObject("player");String who=person==null?"":person.optString("name");
                timeline.put(min+label+" · "+who+" · "+("TEAM_A".equals(e.optString("side"))?name(a):name(b)));}
        }
        section(sections,"Temps forts",timeline);
        JSONObject lineups=m.optJSONObject("lineups");if(lineups!=null)for(String side:new String[]{"teamA","teamB"}){
            JSONObject team=lineups.optJSONObject(side);if(team==null)continue;String title=name("teamA".equals(side)?a:b);
            JSONArray lineup=players(team.optJSONArray("lineup"));String formation=team.optString("formation","");if(!formation.isEmpty())lineup.put("Formation : "+formation);
            section(sections,(lineups.optBoolean("confirmed")?"Titulaires · ":"Composition probable · ")+title,lineup);
            section(sections,"Remplaçants · "+title,players(team.optJSONArray("substitutes")));
            JSONObject manager=team.optJSONObject("manager");if(manager!=null)section(sections,"Entraîneur · "+title,new JSONArray().put(manager.optString("name")));
            section(sections,"Blessés · "+title,players(team.optJSONArray("injuries")));
            section(sections,"Suspendus · "+title,players(team.optJSONArray("suspensions")));
            JSONArray bench=team.optJSONArray("substitutes"),changes=new JSONArray();
            if(bench!=null)for(int bi=0;bi<bench.length();bi++){JSONArray ev=bench.getJSONObject(bi).optJSONArray("events");if(ev==null)continue;for(int ei=0;ei<ev.length();ei++){JSONObject e=ev.getJSONObject(ei),in=e.optJSONObject("in"),out=e.optJSONObject("out"),period=e.optJSONObject("period");if(in!=null&&out!=null)changes.put((period==null?"":period.optInt("minute")+"′ · ")+in.optString("name")+" remplace "+out.optString("name"));}}
            section(sections,"Changements · "+title,changes);
        }
        return new JSONObject().put("sections",sections).put("checked",System.currentTimeMillis());
    }
    private static JSONArray players(JSONArray a)throws Exception{JSONArray rows=new JSONArray();if(a==null)return rows;for(int i=0;i<a.length();i++){JSONObject p=a.getJSONObject(i),person=p.optJSONObject("person");if(person==null)continue;rows.put((p.isNull("shirtNumber")?"":p.optInt("shirtNumber")+" · ")+person.optString("name")+(p.optBoolean("isCaptain")?" (C)":""));}return rows;}
    private static String stat(String s){String[] a={"POSSESSION","SHOT_ON_TARGET","SHOT_OFF_TARGET","SHOT_TOTAL","CORNER_TOTAL","FOUL_COMMITED","OFFSIDE_TOTAL","SAVE_TOTAL","FREE_KICK","YELLOW_CARD_TOTAL","RED_CARD_TOTAL","PASS_TOTAL","PASS_ACCURACY","TACKLE_TOTAL","DUEL_WON"},b={"Possession (%)","Tirs cadrés","Tirs non cadrés","Tirs","Corners","Fautes commises","Hors-jeu","Arrêts","Coups francs","Cartons jaunes","Cartons rouges","Passes","Précision des passes","Tacles","Duels gagnés"};for(int i=0;i<a.length;i++)if(a[i].equals(s))return b[i];return s.replace('_',' ');}
    static JSONObject club(String identity,String name)throws Exception{
        String id=identity.substring(7);if(!id.matches("[A-Za-z0-9_-]+"))throw new IOException("Équipe inconnue");
        JSONObject d=page("كرة-القدم/فريق/"+name+"/مباريات/"+id),team=d.getJSONObject("team");
        if(!id.equals(team.getString("id")))throw new IOException("Équipe incorrecte");
        JSONArray sections=new JSONArray(),rows=new JSONArray();rows.put(name(team));section(sections,"Le club",rows);
        JSONArray ms=d.optJSONArray("matches");List<JSONObject> list=new ArrayList<>();if(ms!=null)for(int i=0;i<ms.length();i++)list.add(ms.getJSONObject(i));
        list.sort(Comparator.comparing(v->v.optString("startDate")));
        List<JSONObject> past=new ArrayList<>(),upcoming=new ArrayList<>();long now=System.currentTimeMillis();
        for(JSONObject m:list){try{long at=OffsetDateTime.parse(m.getString("startDate")).toInstant().toEpochMilli();if(at<=now)past.add(m);else upcoming.add(m);}catch(Exception ignored){}}
        Collections.reverse(past);
        section(sections,"Derniers résultats",matchRows(past));section(sections,"Prochains matchs",matchRows(upcoming));
        try{
            JSONObject roster=page("كرة-القدم/فريق/"+name+"/قائمة-اللاعبين/"+id);
            if(!id.equals(roster.getJSONObject("team").getString("id")))throw new IOException("Équipe incorrecte");
            JSONObject squad=roster.getJSONObject("squad"),coach=squad.optJSONObject("coach");
            if(coach!=null)section(sections,"Entraîneur",new JSONArray().put(coach.optString("name")));
            JSONArray players=squad.optJSONArray("players");rows=new JSONArray();
            if(players!=null)for(int i=0;i<players.length();i++){
                JSONObject entry=players.getJSONObject(i),player=entry.getJSONObject("player"),stats=entry.optJSONObject("stats");String position=player.optString("position");
                position="GOALKEEPER".equals(position)?"Gardien":"DEFENDER".equals(position)?"Défenseur":"MIDFIELDER".equals(position)?"Milieu":"FORWARD".equals(position)?"Attaquant":"";
                String line=(player.isNull("shirtNumber")?"":player.optInt("shirtNumber")+" · ")+player.optString("name")+(position.isEmpty()?"":" · "+position);
                if(stats!=null)line+=" · "+stats.optString("appearances","—")+" matchs · "+stats.optString("goals","—")+" buts · "+stats.optString("assists","—")+" passes décisives · "+stats.optString("yellowCards","—")+" CJ / "+stats.optString("redCards","—")+" CR";
                rows.put(line);
            }
            section(sections,"Effectif et statistiques",rows);
        }catch(Exception unavailable){section(sections,"Effectif",new JSONArray().put("Effectif indisponible pour le moment"));}
        return new JSONObject().put("sections",sections).put("checked",System.currentTimeMillis());
    }
    private static JSONArray matchRows(List<JSONObject> matches)throws Exception{
        JSONArray rows=new JSONArray();for(JSONObject m:matches){if(rows.length()>=12)break;JSONObject score=m.optJSONObject("score");String stamp="";
            try{stamp=OffsetDateTime.parse(m.getString("startDate")).atZoneSameInstant(MatchFeed.zone()).format(DateTimeFormatter.ofPattern("dd/MM HH:mm"));}catch(Exception ignored){}
            rows.put(stamp+" · "+name(m.getJSONObject("teamA"))+"  "+(score==null||score.isNull("teamA")||score.isNull("teamB")?"–":score.get("teamA")+" – "+score.get("teamB"))+"  "+name(m.getJSONObject("teamB")));}return rows;
    }
}
