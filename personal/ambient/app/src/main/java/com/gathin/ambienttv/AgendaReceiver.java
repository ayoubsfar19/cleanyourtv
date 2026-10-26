package com.gathin.ambienttv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores only a previously user-enabled monitor; never configures or wakes the TV. */
public final class AgendaReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !AgendaStore.enabled(context) || !AgendaStore.configured(context)) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !Intent.ACTION_TIME_CHANGED.equals(action) && !Intent.ACTION_TIMEZONE_CHANGED.equals(action)) return;
        try {
            context.startForegroundService(new Intent(context, AgendaService.class).setAction(AgendaService.ACTION_RELOAD));
        } catch (RuntimeException ignored) { /* Android may defer background starts; settings can retry. */ }
    }
}
