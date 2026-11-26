package com.lanzador.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Modo "sustituir inicio": cuando aparece la pantalla de inicio original del
 * televisor (al encenderlo o al pulsar Home), abre Lanzador TV en su lugar.
 * Solo mira qué pantalla se abre; no lee su contenido.
 */
public class HomeWatcherService extends AccessibilityService {

    private final Set<String> homePackages = new HashSet<>();
    private final Set<String> homeActivities = new HashSet<>();
    private long lastLaunch;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        refreshHomes();
    }

    /** Busca todas las pantallas de inicio instaladas, excepto la nuestra. */
    private void refreshHomes() {
        homePackages.clear();
        homeActivities.clear();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> list = getPackageManager().queryIntentActivities(home, 0);
        for (ResolveInfo ri : list) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || pkg.equals("android")
                    || pkg.equals("com.android.settings") || pkg.equals("com.android.tv.settings")) continue;
            homePackages.add(pkg);
            homeActivities.add(ri.activityInfo.name);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null) return;
        if (homePackages.isEmpty()) refreshHomes();
        if (!homePackages.contains(pkg.toString())) return;
        // Solo la pantalla principal del launcher original, no sus paneles (p. ej. entradas HDMI).
        if (cls != null && !homeActivities.contains(cls.toString())) return;

        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunch < 800) return;
        lastLaunch = now;

        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(i);
        } catch (Exception ignored) { }
    }

    @Override
    public void onInterrupt() { }
}
