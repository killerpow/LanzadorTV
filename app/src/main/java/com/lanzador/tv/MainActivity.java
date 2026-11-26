package com.lanzador.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
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
        boolean tv, custom;
    }

    static class Section {
        String name;
        final List<String> apps = new ArrayList<>();
        Section(String n) { name = n; }
    }

    interface NameCallback { void onName(String name); }

    private static final String OTHERS = "Aplicaciones";
    private static final String WALL = "__fondo__";
    private static final int REQ_PICK = 1, REQ_PERM = 2;

    private final Map<String, App> apps = new HashMap<>();
    private final List<Section> sections = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout rows;
    private TextView clock, date;
    private String focusPkg;
    private String pickTarget;
    private ImageView bgImage;
    private View bgShade;

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

        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#0B0F1A"), Color.parseColor("#18203A")}));
        bgImage = new ImageView(this);
        bgImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(bgImage, new FrameLayout.LayoutParams(-1, -1));
        bgShade = new View(this);
        bgShade.setBackgroundColor(Color.parseColor("#99000000"));
        frame.addView(bgShade, new FrameLayout.LayoutParams(-1, -1));

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
        settings.setText("⚙  Ajustes");
        settings.setTextColor(Color.parseColor("#DDDDDD"));
        settings.setTextSize(15);
        settings.setPadding(dp(16), dp(8), dp(16), dp(8));
        makeFocusable(settings, settings);
        settings.setOnClickListener(v -> settingsMenu());
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

        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);
        applyBackground();
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
            File cf = iconFile(pkg);
            if (cf.exists()) {
                Bitmap b = BitmapFactory.decodeFile(cf.getAbsolutePath());
                if (b != null) { d = new BitmapDrawable(getResources(), b); a.custom = true; }
            }
            try {
                if (d != null) { /* imagen personalizada */ }
                else if (tv) {
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
        iv.setScaleType(a.custom ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        if (!a.tv && !a.custom) iv.setPadding(dp(22), dp(14), dp(22), dp(14));
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
        opts.add("Cambiar imagen…");
        acts.add(() -> pickImage(a.pkg));
        if (a.custom) {
            opts.add("Restaurar imagen original");
            acts.add(() -> { iconFile(a.pkg).delete(); focusPkg = a.pkg; reload(); });
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

    // ---------- Ajustes ----------

    private void settingsMenu() {
        List<String> opts = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        opts.add("Secciones…");
        acts.add(this::manageSections);
        opts.add("Cambiar fondo de pantalla…");
        acts.add(() -> pickImage(WALL));
        if (wallFile().exists()) {
            opts.add("Quitar fondo de pantalla");
            acts.add(() -> { wallFile().delete(); applyBackground(); });
        }
        opts.add("Sustituir inicio: " + (watcherEnabled() ? "ACTIVADO" : "DESACTIVADO"));
        acts.add(() -> {
            toast("Activa «Lanzador TV (sustituir inicio)» en la lista");
            openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        });
        opts.add("Ajustes del televisor");
        acts.add(() -> openSettings(Settings.ACTION_SETTINGS));
        new AlertDialog.Builder(this)
                .setTitle("Ajustes")
                .setItems(opts.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .show();
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("No disponible en este televisor");
        }
    }

    private boolean watcherEnabled() {
        String s = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName() + "/");
    }

    // ---------- Imágenes (fondo e iconos personalizados) ----------

    private File iconFile(String pkg) {
        return new File(new File(getFilesDir(), "iconos"), pkg + ".png");
    }

    private File wallFile() {
        return new File(getFilesDir(), "fondo.jpg");
    }

    private void applyBackground() {
        File f = wallFile();
        Bitmap b = f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
        bgImage.setImageBitmap(b);
        bgImage.setVisibility(b != null ? View.VISIBLE : View.GONE);
        bgShade.setVisibility(b != null ? View.VISIBLE : View.GONE);
    }

    private String readPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_IMAGES"
                : "android.permission.READ_EXTERNAL_STORAGE";
    }

    /** Elige una imagen: primero la lista propia (cómoda con el mando), si no, el selector del sistema. */
    private void pickImage(String target) {
        pickTarget = target;
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(readPermission()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{readPermission()}, REQ_PERM);
            return;
        }
        ownPicker();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERM) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) ownPicker();
        else systemPicker();
    }

    private void ownPicker() {
        List<String> names = new ArrayList<>();
        List<Uri> uris = new ArrayList<>();
        Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        try (Cursor c = getContentResolver().query(base,
                new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME},
                null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            while (c != null && c.moveToNext() && names.size() < 300) {
                uris.add(ContentUris.withAppendedId(base, c.getLong(0)));
                names.add(c.getString(1));
            }
        } catch (Exception ignored) { }

        if (names.isEmpty()) {
            toast("No hay imágenes en el televisor. Abriendo el explorador…");
            systemPicker();
            return;
        }
        names.add("Buscar en otra carpeta…");
        new AlertDialog.Builder(this)
                .setTitle(WALL.equals(pickTarget) ? "Elige el fondo" : "Elige la imagen")
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    if (w == uris.size()) systemPicker();
                    else saveImage(uris.get(w), pickTarget);
                })
                .show();
    }

    private void systemPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            try {
                startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_PICK);
            } catch (Exception e2) {
                toast("Este televisor no tiene explorador de archivos. Pasa la imagen a la carpeta Descargas.");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            saveImage(data.getData(), pickTarget);
        }
    }

    /** Reduce la imagen y la guarda dentro de la app (no depende de que el archivo original siga ahí). */
    private void saveImage(Uri uri, String target) {
        if (target == null) return;
        final boolean wall = WALL.equals(target);
        new Thread(() -> {
            boolean ok = false;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(in, null, o);
                }
                int maxW = wall ? 1920 : 640;
                int sample = 1;
                while (o.outWidth / (sample * 2) >= maxW) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                Bitmap b;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    b = BitmapFactory.decodeStream(in, null, o2);
                }
                if (b != null) {
                    File dest = wall ? wallFile() : iconFile(target);
                    File dir = dest.getParentFile();
                    if (dir != null) dir.mkdirs();
                    try (FileOutputStream out = new FileOutputStream(dest)) {
                        b.compress(wall ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG, 90, out);
                    }
                    ok = true;
                }
            } catch (Exception ignored) { }
            final boolean done = ok;
            handler.post(() -> {
                if (!done) { toast("No se pudo leer la imagen"); return; }
                if (wall) applyBackground();
                else { focusPkg = target; reload(); }
            });
        }).start();
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
