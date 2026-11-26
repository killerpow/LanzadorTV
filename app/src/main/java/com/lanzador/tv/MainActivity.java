package com.lanzador.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lanzador TV: pantalla de inicio ligera, sin anuncios, organizada por secciones.
 * - OK: abrir app
 * - Mantener OK: mover la app a otra sección / crear sección / reordenar
 * - Botón ⚙ Secciones: renombrar, ordenar o borrar secciones
 */
public class MainActivity extends Activity {

    static class App {
        String pkg, label;
        ComponentName cn;
        Drawable img;
        boolean tv;
    }

    static class Section {
        String name;
        final List<String> apps = new ArrayList<>();
        Section(String n) { name = n; }
    }

    interface NameCallback { void onName(String name); }

    private static final String OTHERS = "Aplicaciones";

    private final Map<String, App> apps = new HashMap<>();
    private final List<Section> sections = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout rows;
    private TextView clock, date;
    private String focusPkg;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            handler.postDelayed(this, 15000);
        }
    };

    // ---------- Ciclo de vida ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("lanzador", MODE_PRIVATE);
        loadSections();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(48), dp(24), dp(48), dp(8));
        root.setClipChildren(false);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#0B0F1A"), Color.parseColor("#18203A")}));

        // Cabecera: hora + fecha a la izquierda, botón de secciones a la derecha
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout timeBox = new LinearLayout(this);
        timeBox.setOrientation(LinearLayout.VERTICAL);
        clock = new TextView(this);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(38);
        clock.setTypeface(Typeface.DEFAULT_BOLD);
        date = new TextView(this);
        date.setTextColor(Color.parseColor("#9AA3C0"));
        date.setTextSize(15);
        timeBox.addView(clock);
        timeBox.addView(date);
        header.addView(timeBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView settings = new TextView(this);
        settings.setText("⚙  Secciones");
        settings.setTextColor(Color.parseColor("#DDDDDD"));
        settings.setTextSize(15);
        settings.setPadding(dp(16), dp(8), dp(16), dp(8));
        makeFocusable(settings, settings);
        settings.setOnClickListener(v -> manageSections());
        header.addView(settings);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setPadding(0, 0, 0, dp(40));
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override
    public void onBackPressed() {
        // Es la pantalla de inicio: "Atrás" no hace nada.
    }

    // ---------- Apps instaladas ----------

    private void reload() {
        new Thread(() -> {
            Map<String, App> found = queryApps();
            handler.post(() -> {
                apps.clear();
                apps.putAll(found);
                build();
            });
        }).start();
    }

    private Map<String, App> queryApps() {
        PackageManager pm = getPackageManager();
        Map<String, App> m = new HashMap<>();
        addFrom(pm, m, Intent.CATEGORY_LEANBACK_LAUNCHER, true);
        addFrom(pm, m, Intent.CATEGORY_LAUNCHER, false);
        return m;
    }

    private void addFrom(PackageManager pm, Map<String, App> m, String category, boolean tv) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> list;
        try {
            list = pm.queryIntentActivities(i, 0);
        } catch (Exception e) {
            return;
        }
        for (ResolveInfo ri : list) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null) continue;
            String pkg = ai.packageName;
            if (pkg.equals(getPackageName()) || m.containsKey(pkg)) continue;
            App a = new App();
            a.pkg = pkg;
            a.cn = new ComponentName(pkg, ai.name);
            a.label = String.valueOf(ri.loadLabel(pm));
            a.tv = tv;
            Drawable d = null;
            try {
                if (tv) {
                    d = ai.loadBanner(pm);
                    if (d == null && ai.applicationInfo != null) d = ai.applicationInfo.loadBanner(pm);
                }
                if (d == null) d = ri.loadIcon(pm);
            } catch (Exception ignored) { }
            a.img = d;
            m.put(pkg, a);
        }
    }

    private void launch(App a) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(a.tv ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER);
        i.setComponent(a.cn);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            Intent alt = getPackageManager().getLeanbackLaunchIntentForPackage(a.pkg);
            if (alt == null) alt = getPackageManager().getLaunchIntentForPackage(a.pkg);
            try {
                if (alt != null) startActivity(alt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                else toast("No se puede abrir " + a.label);
            } catch (Exception e2) {
                toast("No se puede abrir " + a.label);
            }
        }
    }

    // ---------- Construcción de la pantalla ----------

    private void build() {
        View f = getCurrentFocus();
        if (f != null && f.getTag() instanceof String) focusPkg = (String) f.getTag();

        rows.removeAllViews();
        Set<String> used = new HashSet<>();
        View first = null;

        for (Section s : sections) {
            List<App> list = new ArrayList<>();
            for (String pkg : s.apps) {
                App a = apps.get(pkg);
                if (a != null && used.add(pkg)) list.add(a);
            }
            if (!list.isEmpty()) {
                View v = addRow(s.name, list);
                if (first == null) first = v;
            }
        }

        List<App> others = new ArrayList<>();
        for (App a : apps.values()) if (!used.contains(a.pkg)) others.add(a);
        Collections.sort(others, (x, y) -> x.label.compareToIgnoreCase(y.label));
        if (!others.isEmpty()) {
            View v = addRow(OTHERS, others);
            if (first == null) first = v;
        }

        View target = focusPkg == null ? null : rows.findViewWithTag(focusPkg);
        if (target == null) target = first;
        if (target != null) target.requestFocus();
    }

    /** Añade una fila y devuelve su primera casilla. */
    private View addRow(String title, List<App> list) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(19);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(4), dp(18), 0, dp(4));
        rows.addView(t);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(dp(8), dp(10), dp(8), dp(10));

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setClipChildren(false);
        View first = null;
        for (App a : list) {
            View tile = tile(a);
            line.addView(tile);
            if (first == null) first = tile;
        }
        hs.addView(line);
        rows.addView(hs);
        return first;
    }

    private View tile(App a) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setTag(a.pkg);
        v.setPadding(dp(4), dp(4), dp(4), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(14);
        v.setLayoutParams(lp);

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(a.img);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (!a.tv) iv.setPadding(dp(22), dp(14), dp(22), dp(14));
        v.addView(iv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100)));

        TextView label = new TextView(this);
        label.setText(a.label);
        label.setTextSize(13);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(4), dp(6), dp(4), 0);
        v.addView(label);

        makeFocusable(v, label);
        v.setOnClickListener(x -> launch(a));
        v.setOnLongClickListener(x -> { appMenu(a); return true; });
        return v;
    }

    /** Fondo y animación al recibir el foco del mando. */
    private void makeFocusable(View v, TextView label) {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.parseColor("#1E2640"));
        normal.setCornerRadius(dp(10));
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.parseColor("#2E3A66"));
        focused.setCornerRadius(dp(10));
        focused.setStroke(dp(3), Color.WHITE);

        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(normal);
        label.setTextColor(Color.parseColor("#B8BFD6"));
        v.setOnFocusChangeListener((view, has) -> {
            view.setBackground(has ? focused : normal);
            label.setTextColor(has ? Color.WHITE : Color.parseColor("#B8BFD6"));
            view.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(110).start();
        });
    }

    // ---------- Menús ----------

    private void appMenu(App a) {
        Section cur = sectionOf(a.pkg);
        List<String> opts = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();

        for (Section s : sections) {
            if (s == cur) continue;
            opts.add("Mover a «" + s.name + "»");
            acts.add(() -> move(a.pkg, s));
        }
        opts.add("＋ Nueva sección…");
        acts.add(() -> askName("Nueva sección", "", name -> move(a.pkg, addSection(name))));

        if (cur != null) {
            opts.add("◀ Mover a la izquierda");
            acts.add(() -> shift(cur, a.pkg, -1));
            opts.add("▶ Mover a la derecha");
            acts.add(() -> shift(cur, a.pkg, 1));
            opts.add("Quitar de «" + cur.name + "»");
            acts.add(() -> { cur.apps.remove(a.pkg); focusPkg = a.pkg; save(); build(); });
        }
        opts.add("Información de la app");
        acts.add(() -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + a.pkg)));
            } catch (Exception e) {
                toast("No disponible");
            }
        });

        new AlertDialog.Builder(this)
                .setTitle(a.label)
                .setItems(opts.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .show();
    }

    private void manageSections() {
        List<String> opts = new ArrayList<>();
        for (Section s : sections) opts.add(s.name + "  (" + s.apps.size() + ")");
        opts.add("＋ Nueva sección…");
        new AlertDialog.Builder(this)
                .setTitle("Secciones")
                .setItems(opts.toArray(new String[0]), (d, w) -> {
                    if (w == sections.size()) {
                        askName("Nueva sección", "", name -> { addSection(name); save(); build();
                            toast("Mantén OK en una app para añadirla a «" + name + "»"); });
                    } else {
                        sectionMenu(sections.get(w));
                    }
                })
                .show();
    }

    private void sectionMenu(Section s) {
        String[] opts = {"▲ Subir", "▼ Bajar", "Renombrar", "Borrar sección"};
        new AlertDialog.Builder(this)
                .setTitle(s.name)
                .setItems(opts, (d, w) -> {
                    int i = sections.indexOf(s);
                    switch (w) {
                        case 0: if (i > 0) Collections.swap(sections, i, i - 1); break;
                        case 1: if (i < sections.size() - 1) Collections.swap(sections, i, i + 1); break;
                        case 2: askName("Renombrar", s.name, name -> { s.name = name; save(); build(); }); return;
                        case 3: sections.remove(s); break;
                    }
                    save();
                    build();
                })
                .show();
    }

    private void askName(String title, String initial, NameCallback cb) {
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(initial);
        et.setSelection(initial.length());
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("Aceptar", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (!n.isEmpty()) cb.onName(n);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------- Datos de secciones ----------

    private Section sectionOf(String pkg) {
        for (Section s : sections) if (s.apps.contains(pkg)) return s;
        return null;
    }

    private Section addSection(String name) {
        for (Section s : sections) if (s.name.equalsIgnoreCase(name)) return s;
        Section s = new Section(name);
        sections.add(s);
        return s;
    }

    private void move(String pkg, Section target) {
        for (Section s : sections) s.apps.remove(pkg);
        target.apps.add(pkg);
        focusPkg = pkg;
        save();
        build();
    }

    private void shift(Section s, String pkg, int d) {
        int i = s.apps.indexOf(pkg);
        int j = i + d;
        if (i < 0 || j < 0 || j >= s.apps.size()) return;
        Collections.swap(s.apps, i, j);
        focusPkg = pkg;
        save();
        build();
    }

    private void loadSections() {
        sections.clear();
        String json = prefs.getString("sections", null);
        if (json == null) {
            // Primera vez: secciones por defecto con apps conocidas
            Section cine = new Section("Cine");
            Collections.addAll(cine.apps,
                    "com.disney.disneyplus", "com.amazon.amazonvideo.livingroom",
                    "com.wbd.stream", "com.apple.atve.androidtv.appletv", "com.skyshowtime.skyshowtime.google");
            Section series = new Section("Series");
            Collections.addAll(series.apps,
                    "com.netflix.ninja", "es.plus.yomvi", "com.atresmedia.atresplayer", "es.rtve.play");
            Section musica = new Section("Música");
            Collections.addAll(musica.apps,
                    "com.spotify.tv.android", "com.google.android.youtube.tvmusic", "com.google.android.youtube.tv");
            sections.add(cine);
            sections.add(series);
            sections.add(musica);
            save();
            return;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Section s = new Section(o.getString("name"));
                JSONArray a = o.getJSONArray("apps");
                for (int k = 0; k < a.length(); k++) s.apps.add(a.getString(k));
                sections.add(s);
            }
        } catch (Exception e) {
            sections.clear();
        }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Section s : sections) {
                JSONObject o = new JSONObject();
                o.put("name", s.name);
                o.put("apps", new JSONArray(s.apps));
                arr.put(o);
            }
            prefs.edit().putString("sections", arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    // ---------- Utilidades ----------

    private void updateClock() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        String d = new SimpleDateFormat("EEEE, d 'de' MMMM", Locale.getDefault()).format(now);
        if (!d.isEmpty()) d = d.substring(0, 1).toUpperCase(Locale.getDefault()) + d.substring(1);
        date.setText(d);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
