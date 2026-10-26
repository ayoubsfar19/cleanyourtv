package com.gathin.ambienttv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Local, explicit setup for the private Google Calendar feed and TV reminders. */
public final class AgendaSettingsActivity extends Activity {
    private static final int[] REMINDER_MINUTES = {0, 5, 10, 15, 30, 60};
    private static final int TEXT = 0xfff3f1eb;
    private static final int MUTED = 0xffbfc4ca;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private Switch enabled;
    private EditText address;
    private Spinner reminder;
    private TextView status, feedback, overlayStatus;
    private Button sync, disable, disconnect, allowOverlay;
    private boolean updating, destroyed;

    private final Runnable preferencesChanged = () -> { if (!destroyed) updateState(); };
    private final SharedPreferences.OnSharedPreferenceChangeListener changed = (prefs, key) -> {
        if (key != null && (key.startsWith("seen_") || key.startsWith("done_"))) return;
        ui.removeCallbacks(preferencesChanged);
        ui.post(preferencesChanged);
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // The feed address is a credential; do not retain it in activity snapshots or saved state.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        preferences = AgendaStore.preferences(this);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(72), dp(34), dp(72), dp(40));
        page.setBackgroundColor(0xff10151c);

        TextView title = text("Agenda Google", 28, TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        page.addView(title);
        TextView intro = text("Vos deux prochains rendez-vous sur les photos, avec un rappel discret de 10 secondes sur la TV.", 16, MUTED);
        intro.setPadding(0, dp(8), 0, dp(16));
        page.addView(intro);

        enabled = new Switch(this);
        enabled.setText("Activer l’agenda et les rappels TV");
        enabled.setTextColor(TEXT);
        enabled.setTextSize(18);
        enabled.setPadding(dp(8), dp(10), dp(8), dp(10));
        enabled.setOnCheckedChangeListener((view, on) -> {
            if (updating) return;
            if (on && !AgendaStore.configured(this)) {
                showFeedback("Enregistrez d’abord l’adresse secrète de votre agenda.");
            } else if (on) {
                if (!AgendaService.start(this)) showFeedback("Impossible d’activer l’agenda. Réessayez depuis cet écran.");
                else showFeedback("Agenda activé. La synchronisation démarre.");
            } else {
                AgendaService.stop(this);
                showFeedback("Agenda désactivé. L’adresse et le cache sont conservés.");
            }
            updateState();
        });
        page.addView(enabled, fullWidth());

        page.addView(label("Adresse secrète Google Agenda"));
        address = new EditText(this);
        address.setTextColor(TEXT);
        address.setHintTextColor(0xff929ba5);
        address.setTextSize(17);
        address.setContentDescription("Adresse secrète Google Agenda");
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        address.setTransformationMethod(PasswordTransformationMethod.getInstance());
        address.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        address.setSaveEnabled(false);
        address.setFreezesText(false);
        address.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        address.setPadding(dp(12), dp(10), dp(12), dp(10));
        address.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            saveAndActivate();
            return true;
        });
        page.addView(address, fullWidth());

        TextView help = text("Depuis un ordinateur : calendar.google.com → Paramètres → votre agenda → Intégrer l’agenda → Adresse secrète au format iCal. Gardez ce lien privé ; utilisez l’adresse secrète, pas l’adresse publique.", 14, MUTED);
        help.setPadding(dp(4), dp(8), dp(4), dp(6));
        page.addView(help);

        page.addView(label("Rappel TV par défaut"));
        reminder = new Spinner(this);
        reminder.setContentDescription("Rappel TV par défaut");
        ArrayAdapter<String> choices = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"À l’heure de l’événement", "5 minutes avant", "10 minutes avant",
                        "15 minutes avant", "30 minutes avant", "60 minutes avant"});
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        reminder.setAdapter(choices);
        int storedMinutes = AgendaStore.defaultMinutes(this);
        for (int i = 0; i < REMINDER_MINUTES.length; i++) {
            if (REMINDER_MINUTES[i] == storedMinutes) reminder.setSelection(i);
        }
        page.addView(reminder, new LinearLayout.LayoutParams(dp(310), dp(48)));
        TextView timing = text("Ce délai s’applique si le flux ne contient aucun rappel. Les notifications Google Agenda de l’iPhone se règlent séparément. La TV vérifie environ chaque minute ; Google peut ajouter un délai.", 14, MUTED);
        timing.setPadding(dp(4), dp(5), dp(4), dp(12));
        page.addView(timing);

        LinearLayout actions = row();
        actions.addView(button("Enregistrer et activer", this::saveAndActivate));
        sync = button("Synchroniser", () -> {
            if (!AgendaStore.enabled(this)) return;
            AgendaService.syncNow(this);
            showFeedback("Synchronisation demandée. Le cache reste visible pendant la mise à jour.");
        });
        actions.addView(sync);
        page.addView(actions);

        LinearLayout secondary = row();
        disable = button("Désactiver", () -> {
            AgendaService.stop(this);
            showFeedback("Agenda désactivé. L’adresse et le cache sont conservés.");
            updateState();
        });
        disconnect = button("Déconnecter", () -> {
            AgendaService.stop(this);
            AgendaStore.clear(this);
            address.setText("");
            reminder.setSelection(2);
            showFeedback("Agenda déconnecté. L’adresse et le cache ont été effacés.");
            updateState();
        });
        secondary.addView(disable);
        secondary.addView(disconnect);
        page.addView(secondary);

        feedback = text("", 15, 0xffdfcea5);
        feedback.setPadding(dp(4), dp(10), dp(4), 0);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(feedback);
        status = text("", 14, MUTED);
        status.setPadding(dp(4), dp(10), dp(4), dp(8));
        page.addView(status);

        page.addView(label("Rappels au-dessus des autres applications"));
        overlayStatus = text("", 14, MUTED);
        overlayStatus.setPadding(dp(4), dp(4), dp(4), dp(6));
        page.addView(overlayStatus);
        allowOverlay = button("Autoriser l’affichage au-dessus des applications", this::openOverlaySettings);
        page.addView(allowOverlay);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff10151c);
        scroll.addView(page);
        setContentView(scroll);
        preferences.registerOnSharedPreferenceChangeListener(changed);
        updateState();
        enabled.requestFocus();
    }

    private void saveAndActivate() {
        String value = address.getText().toString().trim();
        int minutes = REMINDER_MINUTES[reminder.getSelectedItemPosition()];
        try {
            if (value.isEmpty()) {
                if (!AgendaStore.configured(this)) {
                    showFeedback("Ajoutez une adresse secrète iCal HTTPS valide.");
                    address.requestFocus();
                    return;
                }
                AgendaStore.setDefaultMinutes(this, minutes);
            } else {
                AgendaStore.configure(this, AgendaFeed.validate(value), minutes);
            }
        } catch (IllegalArgumentException invalid) {
            // Do not surface exception details: an address contains the private feed token.
            showFeedback("Adresse refusée. Utilisez l’adresse secrète iCal HTTPS de Google Agenda.");
            address.requestFocus();
            return;
        } catch (RuntimeException unavailable) {
            showFeedback("Impossible d’enregistrer l’agenda. Réessayez.");
            return;
        }
        address.setText("");
        if (AgendaService.start(this)) showFeedback("Agenda activé. La synchronisation démarre.");
        else showFeedback("Adresse enregistrée. Impossible d’activer l’agenda pour le moment.");
        updateState();
    }

    private void updateState() {
        boolean configured = AgendaStore.configured(this);
        boolean active = configured && AgendaStore.enabled(this);
        updating = true;
        enabled.setChecked(active);
        updating = false;
        address.setHint(configured ? "Adresse secrète enregistrée · saisir pour remplacer" : "https://calendar.google.com/calendar/ical/…/basic.ics");
        sync.setEnabled(active);
        disable.setEnabled(active);
        disconnect.setEnabled(configured);
        String message = AgendaStore.status(this);
        long success = AgendaStore.lastSuccess(this);
        if (success > 0) {
            SimpleDateFormat format = new SimpleDateFormat("d MMMM yyyy 'à' HH:mm", Locale.FRANCE);
            format.setTimeZone(TimeZone.getDefault());
            message += "\nDernière mise à jour : " + format.format(new Date(success));
        } else if (configured) message += "\nAucune synchronisation réussie pour le moment.";
        status.setText(message);
        boolean overlay = Settings.canDrawOverlays(this);
        overlayStatus.setText(overlay
                ? "Autorisation accordée. Les rappels s’affichent pendant 10 secondes sans interrompre la télécommande."
                : "Autorisez cet affichage pour les rappels en dehors des photos. Sans cette autorisation, la carte reste disponible sur les photos.");
        allowOverlay.setVisibility(overlay ? View.GONE : View.VISIBLE);
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException unavailable) {
            showFeedback("Ouvrez les réglages du téléviseur → Applications → Autorisations spéciales → Affichage au-dessus des autres applications.");
        }
    }

    private void showFeedback(String message) { feedback.setText(message); }

    private TextView label(String value) {
        TextView view = text(value, 17, TEXT);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setPadding(dp(4), dp(16), dp(4), dp(6));
        return view;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        return view;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        return row;
    }

    private Button button(String value, Runnable action) {
        Button view = new Button(this);
        view.setText(value);
        view.setAllCaps(false);
        view.setTextColor(TEXT);
        view.setTextSize(15);
        view.setPadding(dp(18), dp(9), dp(18), dp(9));
        view.setMinHeight(dp(46));
        view.setMinimumHeight(dp(46));
        view.setBackground(buttonBackground(false));
        view.setOnFocusChangeListener((button, focus) -> view.setBackground(buttonBackground(focus)));
        view.setOnClickListener(button -> action.run());
        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-2, -2);
        position.topMargin = dp(8);
        position.rightMargin = dp(10);
        view.setLayoutParams(position);
        return view;
    }

    private GradientDrawable buttonBackground(boolean focus) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(focus ? 0xff354553 : 0xff202b36);
        shape.setCornerRadius(dp(10));
        shape.setStroke(dp(1), focus ? 0xffdfcea5 : 0x36ffffff);
        return shape;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) updateState();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(changed);
        ui.removeCallbacksAndMessages(null);
        if (address != null) address.setText("");
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
