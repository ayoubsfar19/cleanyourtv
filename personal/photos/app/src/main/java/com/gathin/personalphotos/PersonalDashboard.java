package com.gathin.personalphotos;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local photos stay local. Only the two city coordinates are sent to Open-Meteo. */
final class PersonalDashboard {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Context context;
    private final TextView clock, date, oujda, sale, source;
    private final TextView[] opponents = new TextView[Football.IDS.length];
    private final TextView[] statuses = new TextView[Football.IDS.length];
    private final int[] clubColors = {0xFFB9588D,0xFFE4D7AA,0xFFE96774,0xFF65CD9A,0xFF59CF9A,0xFFF16C72,0xFF83AE85};
    private final TextView[] scores = new TextView[Football.IDS.length];
    private final TextView[] clubs=new TextView[Football.IDS.length];
    private final android.view.View[] marks=new android.view.View[Football.IDS.length];
    private final TextView[] headings=new TextView[4];
    private TextView tableTitle, attribution;
    private long viewStarted;
    private ExecutorService worker;
    private boolean active;
    private int generation;
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            clock.setText(new SimpleDateFormat("HH:mm", Locale.FRANCE).format(new Date()));
            date.setText(new SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(new Date()));
            ui.postDelayed(this, 1000);
        }
    };
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!active) return;
            final int requestGeneration = generation;
            worker.execute(() -> {
                String a = fetchCity("Oujda", 34.68, -1.91);
                String b = fetchCity("Salé", 34.05, -6.80);
                ui.post(() -> {
                    if (!active || generation != requestGeneration) return;
                    oujda.setText(a);
                    sale.setText(b);
                    source.setText("Open-Meteo · relevés du modèle météo");
                });
            });
            ui.postDelayed(this, 15 * 60_000L);
        }
    };
    private final Runnable scoresTick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            android.content.SharedPreferences cache=GoalService.cache(context);
            boolean enabled=cache.getBoolean("enabled",false);
            if(renderLeague(cache,enabled)) {
                ui.postDelayed(this,1000);
                return;
            }
            tableTitle.setText("FOOTBALL / MES 7 CLUBS");
            header("CLUB","ADVERSAIRE","SCORE","ÉTAT / DATE");
            attribution.setText("Kooora · suivi chaque minute · pages toutes les 15 s");
            for(int i=0;i<scores.length;i++) {
                clubs[i].setText(Football.NAMES[i]);
                marks[i].setBackgroundColor(clubColors[i]);
                String text="Chargement…";
                try {
                    JSONObject match=new JSONObject(cache.getString("team"+i,"{}"));
                    String state=match.getString("status");
                    boolean stale=System.currentTimeMillis()-match.optLong("checked",0)>180_000L || cache.getBoolean("error"+i,false);
                    String label="LIVE".equals(state)?"EN DIRECT "+match.optString("minute")
                            :"RESULT".equals(state)?"Terminé":"À venir";
                    if(!enabled) label="Suivi arrêté";
                    else if(stale) label="Données conservées";
                    String date=java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm",Locale.FRANCE)
                            .withZone(java.time.ZoneId.of("Africa/Casablanca")).format(java.time.Instant.parse(match.getString("start")));
                    text=match.optString("displayScore",match.getString("score"));
                    opponents[i].setText(match.optString("opponent",match.getString("title")));
                    statuses[i].setText(label+"\n"+date);
                    statuses[i].setTextColor("LIVE".equals(state)&&enabled&&!stale?0xFF83FFB5:0xFFD3DBE7);
                    scores[i].setTextColor("LIVE".equals(state)&&enabled&&!stale?0xFF83FFB5:Color.WHITE);
                }catch(Exception ignored) {
                    text="—";
                    opponents[i].setText(enabled?"Chargement…":"Suivi désactivé");
                    statuses[i].setText(enabled?(cache.getBoolean("error"+i,false)?"Indisponible":"En attente"):"Réglages Football");
                }
                scores[i].setText(text);
            }
            ui.postDelayed(this,1000);
        }
    };

    PersonalDashboard(Context context, FrameLayout root) {
        this.context = context;
        viewStarted=android.os.SystemClock.elapsedRealtime();
        float screenWidth=context.getResources().getDisplayMetrics().widthPixels/context.getResources().getDisplayMetrics().density;
        int margin=24;
        // Two fixed city cards at the upper left, with restrained glass-like gradients (no blur).
        LinearLayout weatherPanel=panel();
        TextView weatherTitle=text(10,true);weatherTitle.setText("MÉTÉO  /  MAROC");weatherTitle.setTextColor(0xFF96D6FF);
        weatherPanel.addView(weatherTitle);
        LinearLayout cities=new LinearLayout(context);
        oujda=text(14,true);sale=text(14,true);source=text(9,false);
        oujda.setText("Oujda · Chargement…");sale.setText("Salé · Chargement…");
        oujda.setMaxLines(4);sale.setMaxLines(4);sale.setPadding(dp(14),dp(3),0,dp(3));
        cities.addView(oujda,new LinearLayout.LayoutParams(0,-2,1));
        cities.addView(sale,new LinearLayout.LayoutParams(0,-2,1));
        weatherPanel.addView(cities);source.setText("Open-Meteo");source.setTextColor(0xFFB8C5D8);weatherPanel.addView(source);
        FrameLayout.LayoutParams wp=new FrameLayout.LayoutParams(dp(Math.min(410,screenWidth*.48f)),-2,Gravity.TOP|Gravity.LEFT);
        wp.setMargins(dp(margin),dp(margin),0,0);root.addView(weatherPanel,wp);

        // Clock and date occupy the opposite bottom corner, leaving the photograph's centre clear.
        LinearLayout clockPanel=panel();clockPanel.setGravity(Gravity.RIGHT);
        TextView clockTitle=text(10,true);clockTitle.setText("L’INSTANT PRÉSENT");clockTitle.setTextColor(0xFFBAD7F5);
        clock=text(48,false);clock.setGravity(Gravity.RIGHT);
        date=text(13,false);date.setGravity(Gravity.RIGHT);
        clockPanel.addView(clockTitle);clockPanel.addView(clock);clockPanel.addView(date);
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(Math.min(230,screenWidth*.25f)),-2,Gravity.BOTTOM|Gravity.RIGHT);
        cp.setMargins(0,0,dp(margin),dp(20));root.addView(clockPanel,cp);

        // A real table at bottom left: club, opponent, score and match status/date.
        LinearLayout table=panel();
        tableTitle=text(14,true);tableTitle.setText("FOOTBALL / MES 7 CLUBS");table.addView(tableTitle);
        LinearLayout heading=new LinearLayout(context);
        headings[0]=cell("CLUB",10,0xFF9DAFC5);heading.addView(headings[0],new LinearLayout.LayoutParams(dp(123),dp(23)));
        headings[1]=cell("ADVERSAIRE",10,0xFF9DAFC5);heading.addView(headings[1],new LinearLayout.LayoutParams(0,dp(23),1));
        headings[2]=cell("SCORE",10,0xFF9DAFC5);heading.addView(headings[2],new LinearLayout.LayoutParams(dp(57),dp(23)));
        headings[3]=cell("ÉTAT / DATE",10,0xFF9DAFC5);heading.addView(headings[3],new LinearLayout.LayoutParams(dp(105),dp(23)));
        table.addView(heading);
        String[] shortNames={"Barça","Real Madrid","Atlético Madrid","Real Betis","Raja Casablanca","Wydad Casablanca","AS FAR"};
        for(int i=0;i<scores.length;i++) {
            LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);
            if(i%2==0)row.setBackgroundColor(0x182B4766);
            LinearLayout club=new LinearLayout(context);club.setGravity(Gravity.CENTER_VERTICAL);
            android.view.View stripe=new android.view.View(context);stripe.setBackgroundColor(clubColors[i]);marks[i]=stripe;
            LinearLayout.LayoutParams stripeParams=new LinearLayout.LayoutParams(dp(3),dp(17));stripeParams.rightMargin=dp(7);club.addView(stripe,stripeParams);
            TextView name=cell(shortNames[i],12,Color.WHITE);club.addView(name);clubs[i]=name;
            row.addView(club,new LinearLayout.LayoutParams(dp(123),dp(29)));
            opponents[i]=cell("Chargement…",11,0xFFDDE5F0);
            row.addView(opponents[i],new LinearLayout.LayoutParams(0,dp(29),1));
            scores[i]=cell("—",15,Color.WHITE);scores[i].setTypeface(null,Typeface.BOLD);
            row.addView(scores[i],new LinearLayout.LayoutParams(dp(57),dp(29)));
            statuses[i]=text(9,false);statuses[i].setMaxLines(2);statuses[i].setGravity(Gravity.CENTER_VERTICAL);
            statuses[i].setPadding(0,0,0,0);row.addView(statuses[i],new LinearLayout.LayoutParams(dp(105),dp(29)));
            table.addView(row);
        }
        attribution=text(9,false);attribution.setText("Kooora · suivi chaque minute · score vert : en direct");attribution.setTextColor(0xFF9DAFC5);table.addView(attribution);
        FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(dp(Math.min(660,screenWidth*.66f)),-2,Gravity.BOTTOM|Gravity.LEFT);
        tp.setMargins(dp(margin),0,0,dp(20));root.addView(table,tp);

        TextView keepsake=text(11,true);keepsake.setText("SOUVENIRS  /  DIAPORAMA");keepsake.setTextColor(0xFFE2E9F1);
        FrameLayout.LayoutParams kp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT);
        kp.setMargins(0,dp(margin),dp(margin),0);root.addView(keepsake,kp);
    }

    private void header(String a,String b,String c,String d){
        String[] text={a,b,c,d};for(int i=0;i<4;i++)headings[i].setText(text[i]);
    }

    private static final class LeaguePage {
        final int league,offset,total;final boolean scorers;final JSONObject data;
        LeaguePage(int league,boolean scorers,int offset,int total,JSONObject data){
            this.league=league;this.scorers=scorers;this.offset=offset;this.total=total;this.data=data;
        }
    }

    private boolean renderLeague(android.content.SharedPreferences cache,boolean enabled){
        java.util.ArrayList<LeaguePage> pages=new java.util.ArrayList<>();
        for(int league=0;league<2;league++)for(boolean scorers:new boolean[]{false,true}){
            JSONObject data;
            try{data=new JSONObject(cache.getString(Leagues.key(league,scorers),"{}"));}
            catch(Exception e){data=new JSONObject();}
            org.json.JSONArray rows=data.optJSONArray("rows");
            int count=rows==null?0:rows.length(),total=Math.max(1,(count+6)/7);
            for(int page=0;page<total;page++)pages.add(new LeaguePage(league,scorers,page*7,total,data));
        }
        int selected=(int)(((android.os.SystemClock.elapsedRealtime()-viewStarted)/15_000L)%(pages.size()+1));
        if(selected==0)return false;
        LeaguePage page=pages.get(selected-1);
        tableTitle.setText((page.scorers?"BUTEURS / ":"CLASSEMENT / ")+Leagues.NAMES[page.league]+" · "+(page.offset/7+1)+"/"+page.total);
        if(page.scorers)header("JOUEUR","ÉQUIPE","BUTS","RANG / SAISON");
        else header("ÉQUIPE","J / G / N / P","PTS","DIFF. / SAISON");
        org.json.JSONArray rows=page.data.optJSONArray("rows");
        for(int i=0;i<scores.length;i++){
            clubs[i].setText("");opponents[i].setText("");scores[i].setText("");statuses[i].setText("");
            scores[i].setTextColor(Color.WHITE);statuses[i].setTextColor(0xFFD3DBE7);marks[i].setBackgroundColor(0xFF657B96);
            JSONObject row=rows==null?null:rows.optJSONObject(page.offset+i);
            if(row==null)continue;
            clubs[i].setText(row.optString("name"));opponents[i].setText(row.optString("details"));
            scores[i].setText(String.valueOf(row.optInt("value")));statuses[i].setText(row.optString("extra"));
            for(int club=0;club<Football.IDS.length;club++)if(Football.IDS[club].equals(row.optString("teamId"))){
                marks[i].setBackgroundColor(clubColors[club]);break;
            }
        }
        boolean failed=cache.getBoolean("error-"+Leagues.key(page.league,page.scorers),false);
        long checked=page.data.optLong("checked",0);
        if(rows==null){
            clubs[0].setText(enabled?(failed?"Indisponible":"Chargement…"):"Suivi désactivé");
            attribution.setText("Kooora · activer le suivi dans les réglages Football · pages 15 s");
        }else{
            String at=new SimpleDateFormat("dd/MM HH:mm",Locale.FRANCE).format(new Date(checked));
            String state=!enabled?"Suivi arrêté":failed||System.currentTimeMillis()-checked>20*60_000L?"Données conservées":"Mis à jour";
            attribution.setText("Kooora · "+state+" "+at+" · "+(page.scorers?"Top 10 du site":"Classement complet")+" · pages 15 s");
        }
        return true;
    }

    private LinearLayout panel() {
        LinearLayout panel=new LinearLayout(context);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14),dp(10),dp(14),dp(10));
        GradientDrawable background=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xCC142135,0xBB09111E});
        background.setCornerRadius(dp(14));background.setStroke(dp(1),0x4487A2C5);panel.setBackground(background);
        return panel;
    }

    private TextView cell(String label,int size,int color) {
        TextView v=text(size,false);v.setText(label);v.setTextColor(color);v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);v.setGravity(Gravity.CENTER_VERTICAL);v.setPadding(0,0,dp(5),0);
        return v;
    }

    void start() {
        if (active) return;
        active = true;
        viewStarted=android.os.SystemClock.elapsedRealtime();
        generation++;
        worker = Executors.newSingleThreadExecutor();
        ui.post(clockTick);
        ui.post(refresh);
        ui.post(scoresTick);
    }

    void stop() {
        active = false;
        generation++;
        ui.removeCallbacksAndMessages(null);
        if (worker != null) worker.shutdownNow();
    }

    private String fetchCity(String name, double lat, double lon) {
        String key = name.equals("Oujda") ? "oujda" : "sale";
        android.content.SharedPreferences cache = context.getSharedPreferences("personal-weather", 0);
        try {
            JSONObject data = get(String.format(Locale.ROOT,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code,is_day&timezone=Africa%%2FCasablanca", lat, lon));
            JSONObject current = data.getJSONObject("current");
            double temperature = current.getDouble("temperature_2m");
            int code = current.getInt("weather_code");
            String time = current.getString("time");
            String reading = name + "  " + Math.round(temperature) + " °C  "
                    + Weather.symbol(code, current.optInt("is_day", 1) == 1) + "\n"
                    + condition(code) + " · " + time.replace('T', ' ');
            cache.edit().putString(key, reading).apply();
            return reading;
        } catch (Exception e) {
            String previous = cache.getString(key, "");
            return previous.isEmpty() ? name + "\nMétéo indisponible · nouvelle tentative dans 15 min"
                    : previous + "\nDonnées conservées · connexion indisponible";
        }
    }

    static String condition(int code) {
        if (code < 0 || code > 99) return "Conditions indisponibles";
        if (code == 0) return "Ciel dégagé";
        if (code <= 2) return "Éclaircies";
        if (code == 3) return "Nuageux";
        if (code == 45 || code == 48) return "Brouillard";
        if (code >= 95) return "Orage";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Neige";
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return "Pluie";
        return "Conditions indisponibles";
    }

    private static JSONObject get(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) != -1;) {
                out.write(buffer, 0, n);
                if (out.size() > 128 * 1024) throw new java.io.IOException("Response too large");
            }
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }

    private TextView text(int size, boolean bold) {
        TextView view = new TextView(context);
        view.setTextColor(Color.WHITE);
        view.setTextSize(size);
        view.setShadowLayer(dp(3), 0, dp(1), Color.BLACK);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        view.setPadding(0, dp(3), 0, dp(3));
        return view;
    }

    private int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
