package com.gathin.personalphotos;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public class FootballBoot extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && GoalService.cache(context).getBoolean("enabled",false)){
            try{context.startForegroundService(new Intent(context,GoalService.class));}
            catch(RuntimeException ignored){ }
        }
    }
}
