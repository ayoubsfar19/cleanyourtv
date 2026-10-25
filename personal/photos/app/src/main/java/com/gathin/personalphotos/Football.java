package com.gathin.personalphotos;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the same public match data embedded in Kooora's team pages. No login or private API. */
final class Football {
    static final String[] NAMES = {"FC Barcelona", "Real Madrid", "Atlético Madrid", "Real Betis",
            "Raja Casablanca", "Wydad Casablanca", "AS FAR"};
    static final String[] IDS = {"agh9ifb2mw3ivjusgedj7c3fe", "3kq9cckrnlogidldtdie2fkbl",
            "4ku8o6uf87yd8iecdalipo6wd", "ah8dala7suqqkj04n2l8xz4zd", "3l9kyuwcn5wgjz5b1e3qrazqz",
            "45md2vgytmsj2y0rat0sur1f6", "x0mbz2vi8eyejsx1jc729svv"};
    static final String[] SLUGS = {"برشلونة", "ريال-مدريد", "أتلتيكو-مدريد", "ريال-بيتيس",
            "الرجاء-البيضاوي", "الوداد-الرياضي", "الجيش-الملكي"};
    private static final Pattern DATA = Pattern.compile(
            "<script[^>]*\\bid=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>", Pattern.DOTALL);

    static final class Match {
        final String id, status, home, away, start, minute;
        final int homeScore, awayScore;
        final boolean watchedHome;
        Match(String id, String status, String home, String away, String start, String minute,
              int homeScore, int awayScore, boolean watchedHome) {
            this.id=id; this.status=status; this.home=home; this.away=away; this.start=start;
            this.minute=minute; this.homeScore=homeScore; this.awayScore=awayScore; this.watchedHome=watchedHome;
        }
        int watchedScore() { return watchedHome ? homeScore : awayScore; }
        String score() { return homeScore < 0 || awayScore < 0 ? "–" : homeScore + " - " + awayScore; }
        String title() { return home + "  " + score() + "  " + away; }
        JSONObject json(long checked) throws Exception {
            return new JSONObject().put("id",id).put("status",status).put("title",title())
                    .put("opponent",watchedHome ? away : home)
                    .put("displayScore",homeScore<0 || awayScore<0 ? "–" : watchedScore()+" - "+(watchedHome ? awayScore : homeScore))
                    .put("score",score()).put("start",start).put("minute",minute).put("checked",checked);
        }
    }

    static JSONObject extract(String html) throws Exception {
        Matcher m=DATA.matcher(html);
        if (!m.find()) throw new java.io.IOException("Match data missing");
        return new JSONObject(m.group(1));
    }

    static Match select(JSONObject page, int team, long now) throws Exception {
        JSONObject data=page.getJSONObject("props").getJSONObject("pageProps").getJSONObject("data");
        if (!IDS[team].equals(data.getJSONObject("team").getString("id")))
            throw new java.io.IOException("Wrong team");
        JSONArray matches=data.getJSONArray("matches");
        Match live=null, last=null, next=null;
        long lastAt=Long.MIN_VALUE, nextAt=Long.MAX_VALUE, liveAt=Long.MIN_VALUE;
        for (int i=0;i<matches.length();i++) {
            JSONObject m=matches.getJSONObject(i);
            JSONObject a=m.getJSONObject("teamA"), b=m.getJSONObject("teamB");
            if (!IDS[team].equals(a.optString("id")) && !IDS[team].equals(b.optString("id"))) continue;
            String state=m.optString("status"), start=m.optString("startDate");
            long at;
            try { at=Instant.parse(start).toEpochMilli(); } catch (Exception e) { continue; }
            JSONObject score=m.optJSONObject("score");
            int sa=score==null?-1:score.optInt("teamA",-1), sb=score==null?-1:score.optInt("teamB",-1);
            JSONObject period=m.optJSONObject("period");
            String minute="";
            if (period!=null) {
                if ("HALF_TIME".equals(period.optString("type"))) minute="Mi-temps";
                else if (!period.isNull("minute")) minute=period.optInt("minute")+"′";
            }
            Match item=new Match(m.getString("id"),state,name(a),name(b),start,minute,sa,sb,
                    IDS[team].equals(a.optString("id")));
            // Ignore stale 'LIVE' flags left over from another day.
            if ("LIVE".equals(state) && sa>=0 && sb>=0 && at<=now+15*60_000L && at>=now-8*3600_000L && at>liveAt) {
                live=item; liveAt=at;
            } else if ("RESULT".equals(state) && sa>=0 && sb>=0 && at<=now && at>lastAt) {
                last=item; lastAt=at;
            } else if ("FIXTURE".equals(state) && at>=now && at<nextAt) { next=item; nextAt=at; }
        }
        if (live!=null) return live;
        if (last!=null) return last;
        if (next!=null) return next;
        throw new java.io.IOException("No usable match");
    }

    private static String name(JSONObject team) {
        for (int i=0;i<IDS.length;i++) if (IDS[i].equals(team.optString("id"))) return NAMES[i];
        return team.optString("name","Adversaire");
    }

    static final class GoalGate {
        private Match previous;
        private int maximum;
        int observe(Match current) {
            boolean same=previous!=null && previous.id.equals(current.id);
            int score=current.watchedScore();
            int goals=0;
            if (same && "LIVE".equals(previous.status)
                    && ("LIVE".equals(current.status)||"RESULT".equals(current.status)) && score>maximum)
                goals=score-maximum;
            maximum=same?Math.max(maximum,score):score;
            previous=current;
            return goals;
        }
    }

    static Match fetch(int team) throws Exception {
        String path="كرة-القدم/فريق/"+SLUGS[team]+"/مباريات/"+IDS[team];
        return select(page(path),team,System.currentTimeMillis());
    }

    static JSONObject page(String path) throws Exception {
        String address="https://www.kooora.com/"+android.net.Uri.encode(path,"/");
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
        c.setConnectTimeout(10_000); c.setReadTimeout(10_000);
        try(InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];
            for(int n;(n=in.read(buffer))!=-1;) {
                out.write(buffer,0,n);
                if(out.size()>2*1024*1024) throw new java.io.IOException("Page too large");
            }
            return extract(out.toString("UTF-8"));
        } finally { c.disconnect(); }
    }
}
