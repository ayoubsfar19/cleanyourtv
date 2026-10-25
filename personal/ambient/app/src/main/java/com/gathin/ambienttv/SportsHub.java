package com.gathin.ambienttv;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** Compact ambient chrome; the full sports page exists only after an explicit click. */
final class SportsHub {
    private final Context c;
    private final FrameLayout root;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final android.content.SharedPreferences cache;
    private final TextView launch,weather,notice;
    private FrameLayout page;
    private LinearLayout rows,detail;
    private TextView source,dateLabel;
    private ExecutorService net, art;
    private final android.util.LruCache<String,Bitmap> logos=new android.util.LruCache<String,Bitmap>(4_000_000){protected int sizeOf(String k,Bitmap b){return b.getByteCount();}};
    private final Set<LocalDate> requests=new HashSet<>();
    private ImageView backdrop;
    private android.media.ToneGenerator tone;
    private volatile boolean active;
    private volatile int generation,artGeneration;
    private int filter;
    private LocalDate date=LocalDate.now(MatchFeed.ZONE);
    private List<MatchFeed.Match> matches=new ArrayList<>();
    private MatchFeed.GoalGate goals=new MatchFeed.GoalGate();
    private final ArrayDeque<String> pending=new ArrayDeque<>();
    private boolean showing;
    private long checked;
    private String failure="";
    private final Runnable poll=new Runnable(){public void run(){if(!active)return;refreshToday();ui.postDelayed(this,30_000);}};
    private final Runnable weatherPoll=new Runnable(){public void run(){if(!active)return;refreshWeather();ui.postDelayed(this,30*60_000L);}};

    SportsHub(Context context,FrameLayout container){
        c=context;root=container;cache=c.getSharedPreferences("ambient-scores",0);
        launch=text("⚽  Football",15);launch.setPadding(dp(18),dp(10),dp(18),dp(10));
        focusable(launch);launch.setOnClickListener(v->open());
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-2,dp(44),Gravity.BOTTOM|Gravity.LEFT);lp.leftMargin=dp(32);lp.bottomMargin=dp(27);root.addView(launch,lp);launch.requestFocus();
        weather=text(cache.getString("weather","Oujda  ·  Météo…"),17);weather.setShadowLayer(dp(4),0,1,Color.BLACK);
        FrameLayout.LayoutParams wp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);wp.leftMargin=dp(32);wp.topMargin=dp(28);root.addView(weather,wp);
        notice=text("",17);notice.setPadding(dp(20),dp(12),dp(20),dp(12));notice.setBackground(bg(0xF0182633,0xFFFFD44D,14));notice.setVisibility(View.GONE);
        FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(dp(470),-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);np.topMargin=dp(24);root.addView(notice,np);
    }
    void start(){active=true;generation++;goals=new MatchFeed.GoalGate();net=Executors.newSingleThreadExecutor();art=Executors.newSingleThreadExecutor();ui.post(poll);ui.post(weatherPoll);}
    void stop(){active=false;generation++;ui.removeCallbacksAndMessages(null);if(net!=null)net.shutdownNow();net=null;if(art!=null)art.shutdownNow();art=null;logos.evictAll();requests.clear();if(tone!=null){tone.release();tone=null;}pending.clear();showing=false;notice.setVisibility(View.GONE);back();}
    boolean back(){if(page==null)return false;root.removeView(page);page=null;rows=null;detail=null;backdrop=null;artGeneration++;launch.setVisibility(View.VISIBLE);launch.requestFocus();return true;}
    private int dp(float v){return Math.round(v*c.getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size){TextView t=new TextView(c);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(size);t.setFontFeatureSettings("tnum");return t;}
    private GradientDrawable bg(int color,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(2),stroke);return d;}
    private void focusable(View v){v.setFocusable(true);v.setClickable(true);v.setBackground(bg(0xB5101823,0x55FFFFFF,12));v.setOnFocusChangeListener((w,f)->w.setBackground(bg(f?0xF0182734:0xB5101823,f?0xFFFFD44D:0x33FFFFFF,12)));}
    private LinearLayout column(){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView button(String s,Runnable run){TextView t=text(s,14);t.setGravity(Gravity.CENTER);t.setPadding(dp(15),dp(8),dp(15),dp(8));focusable(t);t.setOnClickListener(v->run.run());return t;}
    private void open(){
        if(page!=null)return;
        page=new FrameLayout(c);page.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xFF008E99,0xFF143B55,0xFF572C79}));
        root.addView(page,new FrameLayout.LayoutParams(-1,-1));launch.setVisibility(View.GONE);
        backdrop=new ImageView(c);backdrop.setScaleType(ImageView.ScaleType.FIT_CENTER);backdrop.setAlpha(0.16f);FrameLayout.LayoutParams artLp=new FrameLayout.LayoutParams(dp(430),dp(430),Gravity.RIGHT|Gravity.CENTER_VERTICAL);page.addView(backdrop,artLp);
        LinearLayout layout=column();layout.setPadding(dp(30),dp(22),dp(30),dp(16));page.addView(layout,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout heading=new LinearLayout(c);heading.setGravity(Gravity.CENTER_VERTICAL);layout.addView(heading,new LinearLayout.LayoutParams(-1,dp(44)));
        TextView title=text("LALIGA  /  Centre des matchs",25);title.setTypeface(null,Typeface.BOLD);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));heading.addView(button("Retour aux photos",()->back()),new LinearLayout.LayoutParams(-2,dp(38)));
        LinearLayout tabs=new LinearLayout(c);layout.addView(tabs,new LinearLayout.LayoutParams(-1,dp(44)));
        String[] names={"Tous les matchs","● En direct","Résultats"};
        for(int i=0;i<names.length;i++){final int f=i;TextView b=button(names[i],()->{filter=f;render();});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(36));p.rightMargin=dp(10);tabs.addView(b,p);}
        LinearLayout dates=new LinearLayout(c);dates.setGravity(Gravity.CENTER_VERTICAL);layout.addView(dates,new LinearLayout.LayoutParams(-1,dp(44)));
        dates.addView(button("‹ Jour précédent",()->changeDay(-1)));dateLabel=text("",16);dateLabel.setGravity(Gravity.CENTER);dates.addView(dateLabel,new LinearLayout.LayoutParams(0,-2,1));dates.addView(button("Aujourd’hui",()->{date=LocalDate.now(MatchFeed.ZONE);loadDay();}));dates.addView(button("Jour suivant ›",()->changeDay(1)));
        LinearLayout body=new LinearLayout(c);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,0,1);bp.topMargin=dp(10);bp.bottomMargin=dp(10);layout.addView(body,bp);
        ScrollView scroll=new ScrollView(c);scroll.setFillViewport(true);scroll.setClipToPadding(false);rows=column();scroll.addView(rows,new ScrollView.LayoutParams(-1,-2));body.addView(scroll,new LinearLayout.LayoutParams(0,-1,2.25f));
        detail=column();detail.setGravity(Gravity.CENTER);detail.setPadding(dp(16),dp(16),dp(16),dp(16));detail.setBackground(bg(0x8810192B,0,16));LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(0,-1,1);dp.leftMargin=dp(16);body.addView(detail,dp);
        source=text("",11);layout.addView(source,new LinearLayout.LayoutParams(-1,-2));
        loadDay();tabs.getChildAt(0).requestFocus();notice.bringToFront();
    }
    private void changeDay(int delta){date=date.plusDays(delta);loadDay();}
    private void loadDay(){
        String raw=cache.getString("day-"+date,"");checked=cache.getLong("at-"+date,0);failure="";
        try{matches=raw.isEmpty()?new ArrayList<>():MatchFeed.parse(raw);}catch(Exception e){matches=new ArrayList<>();}
        render();requestDate(date,false);
    }
    private void refreshToday(){requestDate(LocalDate.now(MatchFeed.ZONE),true);}
    private void requestDate(LocalDate requested,boolean notify){
        if(!active||net==null)return;
        if(requests.contains(requested))return;
        final int g=generation;requests.add(requested);
        net.execute(()->{
            try{
                if(!requested.equals(date)&&!requested.equals(LocalDate.now(MatchFeed.ZONE))){ui.post(()->requests.remove(requested));return;}
                String raw=MatchFeed.fetch(requested);List<MatchFeed.Match> fresh=MatchFeed.parse(raw);long at=System.currentTimeMillis();
                if(!active||generation!=g)return;
                cache.edit().putString("day-"+requested,raw).putLong("at-"+requested,at).apply();
                ui.post(()->{if(!active||generation!=g)return;requests.remove(requested);
                    // Today is observed even when viewing another date; never alert from archived results.
                    if(requested.equals(LocalDate.now(MatchFeed.ZONE)))for(MatchFeed.Match m:fresh){String alert=goals.observe(m,at);if(alert!=null)enqueue(alert);}
                    if(date.equals(requested)){matches=fresh;checked=at;failure="";render();}
                });
            }catch(Exception e){ui.post(()->{if(!active||generation!=g)return;requests.remove(requested);if(date.equals(requested)){failure="Connexion indisponible · données conservées";render();}});}
        });
    }
    private void render(){
        if(page==null||rows==null)return;
        dateLabel.setText(date.format(DateTimeFormatter.ofPattern("EEEE d MMMM",Locale.FRANCE)));
        source.setText("ESPN · "+(checked==0?"Chargement…":"Actualisé à "+Instant.ofEpochMilli(checked).atZone(MatchFeed.ZONE).format(DateTimeFormatter.ofPattern("HH:mm")))+" · suivi 30 s · heures du Maroc"+(failure.isEmpty()?"":"  /  "+failure));
        // Updating scores does not detach the focused card or reset the user's D-pad position.
        View focused=page.findFocus();String focusedId=focused==null?null:(focused.getTag() instanceof String?(String)focused.getTag():null);
        rows.removeAllViews();List<MatchFeed.Match> visible=new ArrayList<>();
        for(MatchFeed.Match m:matches)if(filter==0||filter==1&&m.live()||filter==2&&m.finished())visible.add(m);
        if(visible.isEmpty()){
            TextView empty=text(checked==0?"Chargement des matchs…":filter==1?"Aucun match en direct à cette date":filter==2?"Aucun résultat à cette date":"Aucun match à cette date",18);empty.setPadding(dp(24),dp(35),dp(24),dp(35));rows.addView(empty);detail.removeAllViews();detail.addView(text("LALIGA\n\nChoisissez un autre jour",20));return;
        }
        for(MatchFeed.Match m:visible){
            LinearLayout card=column();card.setPadding(dp(16),dp(10),dp(16),dp(10));focusable(card);card.setTag(m.id);
            TextView state=text(m.label(),11);state.setTextColor(m.live()?0xFFFFD44D:0xFFB9D8DF);card.addView(state);
            LinearLayout line=new LinearLayout(c);line.setGravity(Gravity.CENTER_VERTICAL);card.addView(line,new LinearLayout.LayoutParams(-1,dp(38)));
            TextView h=text(m.home,18),score=text(m.score(),23),a=text(m.away,18);score.setTypeface(null,Typeface.BOLD);score.setGravity(Gravity.CENTER);a.setGravity(Gravity.RIGHT);
            line.addView(h,new LinearLayout.LayoutParams(0,-2,1));line.addView(score,new LinearLayout.LayoutParams(dp(92),-2));line.addView(a,new LinearLayout.LayoutParams(0,-2,1));
            card.setOnClickListener(v->showDetail(m));card.setOnFocusChangeListener((v,f)->{v.setBackground(bg(f?0xEE122333:0xB5101823,f?0xFFFFD44D:0x33FFFFFF,12));if(f)showDetail(m);});
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(76));cp.bottomMargin=dp(8);rows.addView(card,cp);
            if(m.id.equals(focusedId))card.requestFocus();
        }
        if(focusedId==null||detail.getChildCount()==0)showDetail(visible.get(0));
    }
    private void showDetail(MatchFeed.Match m){
        if(detail==null)return;detail.removeAllViews();int token=++artGeneration;
        TextView label=text(m.label(),12);label.setTextColor(0xFFFFD44D);detail.addView(label);
        LinearLayout logos=new LinearLayout(c);logos.setGravity(Gravity.CENTER);ImageView h=new ImageView(c),a=new ImageView(c);h.setScaleType(ImageView.ScaleType.FIT_CENTER);a.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logos.addView(h,new LinearLayout.LayoutParams(dp(74),dp(74)));TextView vs=text("  /  ",18);logos.addView(vs);logos.addView(a,new LinearLayout.LayoutParams(dp(74),dp(74)));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(100));detail.addView(logos,lp);
        TextView teams=text(m.home+"\n"+m.score()+"\n"+m.away,22);teams.setGravity(Gravity.CENTER);detail.addView(teams);
        TextView caption=text("Match Stats",13);caption.setPadding(0,dp(18),0,dp(6));detail.addView(caption);
        TextView stats=text("Sélectionnez le match avec OK\npour ses statistiques",11);stats.setGravity(Gravity.CENTER);detail.addView(stats);
        loadLogo(m.homeLogo,h,token,true);loadLogo(m.awayLogo,a,token,false);
        teams.setContentDescription(m.home+" contre "+m.away+" "+m.score());
        for(int i=0;i<rows.getChildCount();i++)if(m.id.equals(rows.getChildAt(i).getTag()))rows.getChildAt(i).setOnClickListener(v->loadStats(m,stats,token));
    }
    private void loadStats(MatchFeed.Match m,TextView target,int token){
        target.setText("Chargement…");int g=generation;
        net.execute(()->{
            String value;
            try{
                JSONObject r=new JSONObject(MatchFeed.get("https://site.api.espn.com/apis/site/v2/sports/soccer/esp.1/summary?event="+m.id,3_000_000));
                JSONArray teams=r.optJSONObject("boxscore")==null?null:r.getJSONObject("boxscore").optJSONArray("teams");
                StringBuilder out=new StringBuilder();
                if(teams!=null)for(int i=0;i<teams.length();i++){
                    JSONObject team=teams.getJSONObject(i);JSONArray st=team.optJSONArray("statistics");
                    if(st==null)continue;out.append(team.getJSONObject("team").optString("displayName")).append('\n');
                    for(int k=0;k<st.length();k++){JSONObject s=st.getJSONObject(k);String n=s.optString("name"),label=null;
                        if(n.equals("possessionPct"))label="Possession";if(n.equals("shotsOnTarget"))label="Tirs cadrés";if(n.equals("totalShots"))label="Tirs";
                        if(label!=null)out.append(label).append(" : ").append(s.optString("displayValue","—")).append('\n');}
                }
                value=out.length()==0?"Statistiques pas encore disponibles":out.toString();
            }catch(Exception e){value="Statistiques indisponibles\nRéessayez avec OK";}
            final String result=value;ui.post(()->{if(active&&generation==g&&token==artGeneration)target.setText(result);});
        });
    }
    private void loadLogo(String url,ImageView target,int token,boolean background){
        if(!url.startsWith("https://a.espncdn.com/")||art==null)return;int g=generation;
        Bitmap cached=logos.get(url);if(cached!=null){target.setImageBitmap(cached);if(background&&backdrop!=null)backdrop.setImageBitmap(cached);return;}
        art.execute(()->{if(!active||g!=generation||token!=artGeneration)return;Bitmap b=logos.get(url);HttpURLConnection conn=null;
            try{conn=(HttpURLConnection)new URL(url).openConnection();conn.setConnectTimeout(5000);conn.setReadTimeout(5000);
                try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){if(out.size()+n>1_000_000)throw new IOException();out.write(buf,0,n);}byte[] bytes=out.toByteArray();BitmapFactory.Options opt=new BitmapFactory.Options();opt.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,opt);if(opt.outWidth>1024||opt.outHeight>1024)throw new IOException();opt.inJustDecodeBounds=false;opt.inSampleSize=2;b=BitmapFactory.decodeByteArray(bytes,0,bytes.length,opt);}
            }catch(Exception ignored){}finally{if(conn!=null)conn.disconnect();}
            Bitmap result=b;if(result!=null)logos.put(url,result);ui.post(()->{if(active&&g==generation&&token==artGeneration){target.setImageBitmap(result);if(background&&backdrop!=null)backdrop.setImageBitmap(result);}});
        });
    }
    private void refreshWeather(){int g=generation;net.execute(()->{try{String w="Oujda  ·  "+Weather.now(34.68,-1.91);cache.edit().putString("weather",w).apply();ui.post(()->{if(active&&g==generation)weather.setText(w);});}catch(Exception e){ui.post(()->{if(active&&g==generation)weather.setText(cache.getString("weather","Oujda · Météo indisponible")+" · hors ligne");});}});}
    private void enqueue(String s){if(pending.size()<8)pending.add(s);if(!showing)nextNotice();}
    private void nextNotice(){if(!active)return;String s=pending.poll();if(s==null){showing=false;notice.setVisibility(View.GONE);return;}showing=true;
        String scorer=s.split("\\n")[0];
        if(scorer.contains("Barcelona")){try{if(tone==null)tone=new android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION,60);tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK,500);}catch(RuntimeException ignored){}}
        notice.setText(s);notice.setVisibility(View.VISIBLE);notice.bringToFront();ui.postDelayed(this::nextNotice,10_000);}
}
