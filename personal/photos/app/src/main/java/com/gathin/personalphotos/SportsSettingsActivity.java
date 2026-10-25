package com.gathin.personalphotos;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class SportsSettingsActivity extends Activity {
    private TextView status;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);
        int p=Math.round(32*getResources().getDisplayMetrics().density);list.setPadding(p,p,p,p);
        TextView title=new TextView(this);title.setText("Football · les 7 clubs");title.setTextSize(28);list.addView(title);
        status=new TextView(this);status.setTextSize(18);list.addView(status);
        TextView note=new TextView(this);note.setText("Derniers résultats et scores en direct selon Kooora. Actualisation toutes les 60 secondes quand la TV est allumée, avec le délai du site. Les noms des adversaires peuvent être en arabe. Seuls les buts du Barça ont un son. Le premier chargement ne déclenche jamais un but.");list.addView(note);
        add(list,"1. Autoriser les bandeaux sur les vidéos",()->{
            try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))); }
            catch(android.content.ActivityNotFoundException e) {startActivity(new Intent(Settings.ACTION_SETTINGS));}
        });
        add(list,"2. Activer les scores et alertes",()->{
            GoalService.cache(this).edit().putBoolean("enabled",true).apply();
            startForegroundService(new Intent(this,GoalService.class));update();
        });
        add(list,"TEST Barça : bandeau + son",()->{
            if(!GoalService.cache(this).getBoolean("enabled",false))return;
            startForegroundService(new Intent(this,GoalService.class).setAction("TEST_BARCA"));
        });
        add(list,"Désactiver les scores et alertes",()->{
            GoalService.cache(this).edit().putBoolean("enabled",false).apply();
            stopService(new Intent(this,GoalService.class));update();
        });
        add(list,"Afficher les photos",()->startActivity(new Intent(this,SlideshowActivity.class)));
        ScrollView scroll=new ScrollView(this);scroll.addView(list);setContentView(scroll);update();
    }
    private void add(LinearLayout list,String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setOnClickListener(v->action.run());list.addView(b);
    }
    private void update(){status.setText("Scores : "+(GoalService.cache(this).getBoolean("enabled",false)?"activés":"désactivés")
            +"\nBandeaux au-dessus des vidéos : "+(Settings.canDrawOverlays(this)?"autorisés":"permission à accorder"));}
    @Override protected void onResume(){super.onResume();if(status!=null)update();}
}
