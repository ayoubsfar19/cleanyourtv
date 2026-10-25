package com.gathin.personalphotos;
import org.json.JSONArray;
import org.json.JSONObject;

/** The two requested leagues only; standings and the source's top ten scorers. */
final class Leagues {
    static final String[] IDS={"34pl8szyvrbwcmfkuocjm3r6t","1eruend45vd20g9hbrpiggs5u"};
    static final String[] SLUGS={"الدوري-الإسباني","الدوري-المغربي-الممتاز"};
    static final String[] NAMES={"ESPAGNE / LALIGA","MAROC / BOTOLA PRO"};
    static String key(int league,boolean scorers){return "league"+league+(scorers?"scorers":"standings");}
    static JSONObject fetch(int league,boolean scorers) throws Exception {
        return normalize(Football.page("كرة-القدم/مسابقة/"+SLUGS[league]+"/"+
                (scorers?"أفضل-اللاعبين":"جدول")+"/"+IDS[league]),league,scorers,System.currentTimeMillis());
    }
    static JSONObject normalize(JSONObject page,int league,boolean scorers,long checked) throws Exception {
        JSONObject data=page.getJSONObject("props").getJSONObject("pageProps").getJSONObject("data");
        if(!IDS[league].equals(data.getJSONObject("competition").getString("id")))
            throw new java.io.IOException("Wrong competition");
        String season="";JSONArray seasons=data.getJSONArray("seasons");
        for(int i=0;i<seasons.length();i++) if(seasons.getJSONObject(i).optBoolean("active")){
            season=seasons.getJSONObject(i).getString("name");break;
        }
        if(season.isEmpty())throw new java.io.IOException("Missing active season");
        JSONArray rows=new JSONArray();String updated="";
        if(scorers){
            JSONArray goals=data.getJSONObject("topPlayers").getJSONArray("goals");
            int rank=0,previous=-1;
            for(int i=0;i<goals.length()&&i<10;i++){
                JSONObject goal=goals.getJSONObject(i),team=goal.getJSONObject("team");
                int value=goal.getInt("value");if(value<0)throw new java.io.IOException("Invalid goals");
                if(value!=previous)rank++;previous=value;
                rows.put(new JSONObject().put("name",goal.getJSONObject("player").getString("name"))
                        .put("teamId",team.getString("id")).put("details",teamName(team))
                        .put("value",value).put("extra","#"+rank+" · "+season));
            }
        }else{
            JSONArray tables=data.getJSONObject("standings").getJSONArray("tables");
            if(tables.length()!=1)throw new java.io.IOException("Expected a single league table");
            JSONObject table=tables.getJSONObject(0);updated=table.optString("lastUpdatedAt");
            JSONArray rankings=table.getJSONArray("rankings");
            for(int i=0;i<rankings.length();i++){
                JSONObject ranking=rankings.getJSONObject(i),team=ranking.getJSONObject("team");
                int position=ranking.getInt("position"),points=ranking.getInt("points");
                if(position<=0)throw new java.io.IOException("Invalid rank");
                int difference=ranking.getInt("goalsDifference");
                rows.put(new JSONObject().put("name",position+". "+teamName(team)).put("teamId",team.getString("id"))
                        .put("details",ranking.getInt("played")+" / "+ranking.getInt("win")+" / "+ranking.getInt("draw")+" / "+ranking.getInt("lose"))
                        .put("value",points).put("extra",(difference>0?"+":"")+difference+" · "+season));
            }
        }
        if(rows.length()==0)throw new java.io.IOException("No standings or scorers yet");
        return new JSONObject().put("rows",rows).put("season",season).put("checked",checked).put("sourceUpdated",updated);
    }
    private static String teamName(JSONObject team){
        for(int i=0;i<Football.IDS.length;i++)if(Football.IDS[i].equals(team.optString("id")))return Football.NAMES[i];
        return team.optString("name","Équipe");
    }
}
