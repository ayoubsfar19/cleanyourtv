package com.gathin.personalphotos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Explicitly enabled foreground polling. No accessibility service or remote-key interception. */
public class GoalService extends Service {
    static final String CHANNEL="football-live";
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Football.GoalGate[] gates=new Football.GoalGate[Football.IDS.length];
    private ScheduledExecutorService worker;
    private TextView banner;
    private ToneGenerator tone;
    private volatile boolean stopped;
    private long leaguesChecked;
    private final java.util.ArrayDeque<Alert> alerts=new java.util.ArrayDeque<>();
    private boolean presenting;
    private static final class Alert {
        final String text; final boolean sound;
        Alert(String text,boolean sound){this.text=text;this.sound=sound;}
    }

    static SharedPreferences cache(Context c) { return c.getSharedPreferences("football",0); }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Scores et buts",NotificationManager.IMPORTANCE_LOW));
        PendingIntent settings=PendingIntent.getActivity(this,0,new Intent(this,SportsSettingsActivity.class),PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("Scores des 7 clubs")
                .setContentText("Kooora · actualisation chaque minute · son pour les buts du Barça")
                .setContentIntent(settings).setOngoing(true).build();
        startForeground(31,notification);
        for(int i=0;i<gates.length;i++) gates[i]=new Football.GoalGate();
        worker=Executors.newSingleThreadScheduledExecutor();
        worker.scheduleWithFixedDelay(this::refresh,0,60,TimeUnit.SECONDS);
    }

    private void refresh() {
        if(stopped) return;
        PowerManager pm=getSystemService(PowerManager.class);
        if(pm!=null && !pm.isInteractive()) return;
        for(int i=0;i<Football.IDS.length && !stopped;i++) {
            final int team=i;
            try {
                Football.Match m=Football.fetch(team);
                if(stopped) return;
                cache(this).edit().putString("team"+team,m.json(System.currentTimeMillis()).toString())
                        .remove("error"+team).apply();
                int goals=gates[team].observe(m);
                if(goals>0) ui.post(()->showGoal((goals==1?"BUT":goals+" BUTS")+" · "+Football.NAMES[team]+"\n"+m.title(),team==0));
            } catch(Exception e) {
                cache(this).edit().putBoolean("error"+team,true).apply();
            }
        }
        if(!stopped && System.currentTimeMillis()-leaguesChecked>=10*60_000L){
            leaguesChecked=System.currentTimeMillis();
            for(int league=0;league<Leagues.IDS.length && !stopped;league++){
                for(boolean scorers:new boolean[]{false,true}){
                    if(stopped)return;
                    String key=Leagues.key(league,scorers);
                    try{JSONObject data=Leagues.fetch(league,scorers);
                        cache(this).edit().putString(key,data.toString()).remove("error-"+key).apply();
                    }catch(Exception e){cache(this).edit().putBoolean("error-"+key,true).apply();}
                }
            }
        }
    }

    private void showGoal(String message,boolean sound) {
        if(stopped) return;
        if(alerts.size()<14) alerts.add(new Alert(message,sound));
        if(!presenting) showNext();
    }

    private void showNext() {
        if(stopped)return;
        Alert alert=alerts.poll();
        if(alert==null){presenting=false;return;}
        presenting=true;
        if(Settings.canDrawOverlays(this)) {
            hideBanner();
            TextView view=new TextView(this);
            view.setText(alert.text); view.setTextSize(23); view.setTextColor(Color.WHITE);
            int p=Math.round(18*getResources().getDisplayMetrics().density);
            view.setPadding(p,p,p,p);
            GradientDrawable bg=new GradientDrawable();bg.setColor(0xF010263B);bg.setCornerRadius(p);
            view.setBackground(bg);
            WindowManager.LayoutParams lp=new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;lp.y=p*2;
            try {getSystemService(WindowManager.class).addView(view,lp);banner=view;
            } catch(RuntimeException ignored) { /* Permission revoked or app/firmware hides overlays. */ }
        }
        if(alert.sound) {
            if(tone!=null)tone.release();
            try {tone=new ToneGenerator(AudioManager.STREAM_NOTIFICATION,65);
                tone.startTone(ToneGenerator.TONE_PROP_ACK,500);
            } catch(RuntimeException ignored) { }
        }
        ui.postDelayed(()->{hideBanner();showNext();},10_000);
    }

    private void hideBanner() {
        if(banner==null)return;
        try {getSystemService(WindowManager.class).removeView(banner);}catch(RuntimeException ignored){}
        banner=null;
    }

    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if(intent!=null && "TEST_BARCA".equals(intent.getAction()))
            showGoal("TEST — FC Barcelona\nEssai du bandeau et du son · aucun but réel",true);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        stopped=true;ui.removeCallbacksAndMessages(null);
        alerts.clear();
        if(worker!=null)worker.shutdownNow();hideBanner();
        if(tone!=null)tone.release();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) {return null;}
}
