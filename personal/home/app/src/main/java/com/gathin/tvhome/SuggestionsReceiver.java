package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Explicit, WRITE_SECURE_SETTINGS-protected ADB access to the suggestions choice. */
public final class SuggestionsReceiver extends BroadcastReceiver {
    public static final String EXTRA_ENABLED = "enabled";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        Object enabled;
        try {
            Bundle extras = intent.getExtras();
            if (extras == null || !extras.containsKey(EXTRA_ENABLED)) return;
            enabled = extras.get(EXTRA_ENABLED);
        } catch (RuntimeException ignored) {
            // Malformed or unparcelable extras are not a request to change the choice.
            return;
        }
        if (!(enabled instanceof Boolean)) return;
        SuggestionsSettings.setEnabled(context, (Boolean) enabled);
    }
}
