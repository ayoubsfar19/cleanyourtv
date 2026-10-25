package com.gathin.tvhome;

import android.content.Context;

/** The owner's persistent choice to show recommendation shelves on Home. */
public final class SuggestionsSettings {
    public static final String KEY = "show_suggestions";

    private SuggestionsSettings() {
    }

    public static boolean enabled(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY, enabled).apply();
    }
}
