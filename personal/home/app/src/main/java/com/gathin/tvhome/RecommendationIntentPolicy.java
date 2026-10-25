package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.net.URISyntaxException;

/** Recommendation links can only open an activity belonging to the selected app. */
final class RecommendationIntentPolicy {
    private RecommendationIntentPolicy() { }

    /** Returns a scoped deep link, the app's normal entry point, or null if it cannot be opened. */
    static Intent resolve(Context context, String owner, String encodedUri) {
        if (owner == null || owner.isEmpty()) return null;
        PackageManager pm = context.getPackageManager();
        if (encodedUri != null && !encodedUri.isEmpty()) {
            try {
                Intent candidate = Intent.parseUri(encodedUri, Intent.URI_INTENT_SCHEME);
                ComponentName component = candidate.getComponent();
                String requestedPackage = candidate.getPackage();
                // A foreign explicit target is invalid, even if setting our package would mask it.
                if ((component == null || owner.equals(component.getPackageName()))
                        && (requestedPackage == null || owner.equals(requestedPackage))) {
                    sanitize(candidate, owner);
                    Intent verified = verify(pm, owner, candidate);
                    if (verified != null) return verified;
                }
            } catch (URISyntaxException | RuntimeException ignored) {
                // A stale or malformed recommendation still opens the selected app normally.
            }
        }
        try {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(owner);
            if (launch == null) launch = pm.getLaunchIntentForPackage(owner);
            if (launch == null) return null;
            sanitize(launch, owner);
            return verify(pm, owner, launch);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void sanitize(Intent intent, String owner) {
        intent.setSelector(null);
        intent.setClipData(null);
        intent.setSourceBounds(null);
        intent.setPackage(owner);
        // Drop all provider-supplied launch/grant flags; the launcher chooses its own task behavior.
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static Intent verify(PackageManager pm, String owner, Intent intent) {
        ResolveInfo resolved = pm.resolveActivity(intent, 0);
        ActivityInfo activity = resolved == null ? null : resolved.activityInfo;
        if (activity == null || !owner.equals(activity.packageName) || !activity.exported
                || !activity.enabled || activity.applicationInfo == null
                || !activity.applicationInfo.enabled) return null;
        // Freeze the resolved owner activity: no resolver or browser can receive this intent later.
        intent.setComponent(new ComponentName(activity.packageName, activity.name));
        return intent;
    }
}
