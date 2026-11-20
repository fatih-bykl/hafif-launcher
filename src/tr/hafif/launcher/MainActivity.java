package tr.hafif.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hafif, reklamsız TV ana ekranı: üstte favoriler, altta diğer uygulamalar.
 * Kumandadaki MENÜ tuşu veya OK'a uzun basma: favoriye ekle/çıkar, sırasını değiştir.
 */
public class MainActivity extends Activity {

    // İlk açılışta favori olarak gösterilecek yaygın TV uygulamaları (yalnızca yüklü olanlar görünür).
    // Kendi listenizi ADB ile verebilirsiniz: am start -n tr.hafif.launcher/.MainActivity --es set_favs "paket1,paket2"
    private static final String[] DEFAULT_FAVS = {
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.google.android.youtube.tvmusic",
            "com.spotify.tv.android",
            "tv.twitch.android.app",
            "org.xbmc.kodi",
            "com.plexapp.android",
            "com.google.android.videos",
    };

    private static final int COLS = 5;
    private static final int SIDE = 24; // dp: odak büyümesi için kenarda çok küçük pay
    private static final int BG = 0xFF0E1116;
    private static final int CARD = 0xFF1C2230;
    private static final int ACCENT = 0xFF4FC3F7;
    private static final int RING = 0xFF1E88FF; // seçili kart çerçevesi (canlı mavi)

    private SharedPreferences prefs;
    private PackageManager pm;
    private float dp;

    private LinearLayout favRow;
    private LinearLayout allBox;
    private TextView allTitle;
    private HorizontalScrollView favScroll;

    private final Map<String, ResolveInfo> apps = new HashMap<>();
    private final ArrayList<String> favs = new ArrayList<>();
    private String focusPkg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        pm = getPackageManager();
        dp = getResources().getDisplayMetrics().density;
        getWindow().getDecorView().setBackgroundColor(BG);
        setContentView(buildRoot());
        // Tüm Uygulamalar: odak orada değilken soluk ve koyu, odak inince belirgin
        setAllAppsActive(false, false);
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(
                (oldF, newF) -> setAllAppsActive(isInside(newF, allBox), true));
        applyFavsExtra(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        applyFavsExtra(intent);
    }

    /** ADB ile favori listesi verme: am start ... --es set_favs "paket1,paket2" */
    private void applyFavsExtra(Intent intent) {
        String s = intent == null ? null : intent.getStringExtra("set_favs");
        if (s != null) {
            prefs.edit().putString("favs", s.replace(" ", "")).apply();
            focusPkg = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (netMeter != null) netMeter.stop();
    }

    private NetMeter netMeter;

    @Override
    protected void onResume() {
        super.onResume();
        if (netMeter != null) netMeter.start();
        reload();
    }

    @Override
    public void onBackPressed() {
        // Ana ekranda geri tuşu bir şey yapmaz.
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    // ---------- Arayüz ----------

    private View buildRoot() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, px(24), 0, px(28));
        root.setClipChildren(false);
        root.setClipToPadding(false);
        scroll.addView(root);

        // Üst çubuk: solda saat ve hemen altında aynı genişlikte tarih; sağda hız göstergesi ve ayarlar çarkı
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        // Seçili butonun büyüme efekti ve çerçevesi kenarda kesilmesin
        top.setClipChildren(false);
        top.setClipToPadding(false);

        LinearLayout timeBox = new LinearLayout(this);
        timeBox.setOrientation(LinearLayout.VERTICAL);

        final TextClock clock = new TextClock(this);
        clock.setFormat12Hour("HH:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(36);
        clock.setTypeface(Typeface.DEFAULT_BOLD);
        clock.setIncludeFontPadding(false);
        timeBox.addView(clock);

        final TextClock date = new TextClock(this);
        date.setFormat12Hour("d MMMM yyyy");
        date.setFormat24Hour("d MMMM yyyy");
        date.setTextColor(0xFFB0B8C4);
        date.setTextSize(14);
        date.setIncludeFontPadding(false);
        date.setSingleLine(true);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = -px(6); // saatin alt boşluğunu (font inişi) telafi eder, tarih saate yaklaşır
        timeBox.addView(date, dlp);

        // Tarihin genişliği saatin genişliğine eşit olsun (saat/tarih değiştikçe yeniden ayarlanır)
        View.OnLayoutChangeListener fit = (v, l, t, r, b, ol, ot, or, ob) -> v.post(() -> fitWidth(clock, date));
        clock.addOnLayoutChangeListener(fit);
        date.addOnLayoutChangeListener(fit);

        top.addView(timeBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        netMeter = new NetMeter(this, dp);
        styleFocus(netMeter.view, px(12));
        top.addView(netMeter.view);

        // Ayarlar: aynı boyutta yuvarlak buton, içinde vektör çark simgesi
        FrameLayout settings = new FrameLayout(this);
        settings.setContentDescription("Ayarlar");
        settings.setFocusable(true);
        styleFocus(settings, px(26));
        ImageView gear = new ImageView(this);
        int gearId = getResources().getIdentifier("ic_settings", "drawable", getPackageName());
        if (gearId != 0) gear.setImageResource(gearId);
        gear.setScaleType(ImageView.ScaleType.FIT_CENTER);
        settings.addView(gear, new FrameLayout.LayoutParams(px(32), px(32), Gravity.CENTER));
        settings.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(px(52), px(52));
        slp.leftMargin = px(16);
        top.addView(settings, slp);
        top.setPadding(px(SIDE + 8), 0, px(SIDE + 8), 0);
        // Sabit yükseklik: test sırasında büyüyen gösterge sayfayı aşağı itmesin, üstte taşsın
        top.setTranslationZ(px(4));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(66)));

        root.addView(sectionTitle("Favoriler"));
        favScroll = new HorizontalScrollView(this);
        favScroll.setHorizontalScrollBarEnabled(false);
        favScroll.setClipChildren(false);
        favScroll.setClipToPadding(false);
        favScroll.setPadding(px(SIDE), px(8), px(SIDE), px(8));
        favRow = new LinearLayout(this);
        favRow.setOrientation(LinearLayout.HORIZONTAL);
        favRow.setClipChildren(false);
        favScroll.addView(favRow);
        root.addView(favScroll);

        allTitle = sectionTitle("Tüm Uygulamalar");
        root.addView(allTitle);
        allBox = new LinearLayout(this);
        allBox.setOrientation(LinearLayout.VERTICAL);
        allBox.setClipChildren(false);
        allBox.setPadding(px(SIDE), px(4), px(SIDE), 0);
        root.addView(allBox);

        TextView hint = new TextView(this);
        hint.setText("İpucu: Bir uygulamada MENÜ tuşuna ya da OK'a uzun basarak favorilere ekleyebilir, çıkarabilir veya yerini değiştirebilirsiniz.");
        hint.setTextColor(0xFF6B7380);
        hint.setTextSize(13);
        hint.setPadding(px(SIDE + 8), px(24), px(SIDE + 8), 0);
        root.addView(hint);

        return scroll;
    }

    // ---------- Tüm Uygulamalar soluklaştırma ----------

    private static final float DIM_ALPHA = 0.45f;     // odak yokken saydamlık
    private static final float DIM_SATURATION = 0.2f; // odak yokken renk doygunluğu
    private Boolean allActive;
    private android.animation.ValueAnimator dimAnim;
    private float dimLevel = 1f; // 1 = tam belirgin, 0 = soluk
    private final android.graphics.Paint dimPaint = new android.graphics.Paint();

    private static boolean isInside(View v, View parent) {
        while (v != null) {
            if (v == parent) return true;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return false;
    }

    private void setAllAppsActive(boolean active, boolean animate) {
        if (allActive != null && allActive == active) return;
        allActive = active;
        if (dimAnim != null) dimAnim.cancel();
        float to = active ? 1f : 0f;
        if (!animate) {
            applyDim(to);
            return;
        }
        dimAnim = android.animation.ValueAnimator.ofFloat(dimLevel, to);
        dimAnim.setDuration(220);
        dimAnim.addUpdateListener(a -> applyDim((float) a.getAnimatedValue()));
        dimAnim.start();
    }

    private void applyDim(float level) {
        dimLevel = level;
        float alpha = DIM_ALPHA + (1f - DIM_ALPHA) * level;
        float sat = DIM_SATURATION + (1f - DIM_SATURATION) * level;
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(sat);
        dimPaint.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        if (level >= 1f) {
            allBox.setLayerType(View.LAYER_TYPE_NONE, null); // tam belirginken katman gereksiz
        } else {
            allBox.setLayerType(View.LAYER_TYPE_HARDWARE, dimPaint);
        }
        allBox.setAlpha(alpha);
        allTitle.setAlpha(alpha);
    }

    /** Alttaki metnin yazı boyunu, genişliği üstteki metninkine eşit olacak şekilde ayarlar. */
    private static void fitWidth(TextView ref, TextView target) {
        float rw = ref.getPaint().measureText(ref.getText().toString());
        float tw = target.getPaint().measureText(target.getText().toString());
        if (rw <= 0 || tw <= 0) return;
        float size = target.getTextSize() * rw / tw;
        if (Math.abs(size - target.getTextSize()) > 0.5f) {
            target.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size);
        }
    }

    private TextView sectionTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFFE6EAF0);
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(px(SIDE + 8), px(22), 0, px(4));
        return t;
    }

    private void styleFocus(View v, float radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(radius);
        v.setBackground(bg);
        // Çerçeve, afişin (banner) altında kalmasın diye ön plana çizilir
        final GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(radius);
        ring.setStroke(px(5), RING);
        v.setOnFocusChangeListener((view, has) -> {
            if (view instanceof FrameLayout) {
                ((FrameLayout) view).setForeground(has ? ring : null);
            } else {
                bg.setStroke(has ? px(4) : 0, RING);
            }
            float s = has ? 1.1f : 1f;
            view.animate().scaleX(s).scaleY(s).setDuration(110).start();
            view.setElevation(has ? px(8) : 0);
            if (has) {
                Object tag = view.getTag();
                focusPkg = tag instanceof String ? (String) tag : null;
            }
        });
    }

    private View card(String pkg, int w, int h) {
        ResolveInfo ri = apps.get(pkg);
        FrameLayout f = new FrameLayout(this);
        f.setTag(pkg);
        f.setFocusable(true);
        f.setClipToOutline(true);
        styleFocus(f, px(10));

        CharSequence label = ri.loadLabel(pm);
        f.setContentDescription(label);

        Drawable banner = ri.activityInfo.loadBanner(pm);
        if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);

        if (banner != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(banner);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(ri.loadIcon(pm));
            col.addView(iv, new LinearLayout.LayoutParams(px(44), px(44)));
            TextView tv = new TextView(this);
            tv.setText(label);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(13);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(px(6), px(6), px(6), 0);
            col.addView(tv, new LinearLayout.LayoutParams(-1, -2));
            f.addView(col, new FrameLayout.LayoutParams(-1, -1));
        }

        f.setOnClickListener(v -> openApp(pkg));
        f.setOnLongClickListener(v -> {
            showMenu(pkg);
            return true;
        });
        f.setOnKeyListener((v, code, ev) -> {
            // Satırın ucunda odak başka satıra/üst çubuğa kaçmasın
            if (ev.getAction() == KeyEvent.ACTION_DOWN
                    && (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT)
                    && v.getParent() instanceof ViewGroup) {
                ViewGroup par = (ViewGroup) v.getParent();
                int pos = par.indexOfChild(v);
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && pos == par.getChildCount() - 1) return true;
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && pos == 0) return true;
            }
            if (code == KeyEvent.KEYCODE_MENU && ev.getAction() == KeyEvent.ACTION_UP) {
                showMenu(pkg);
                return true;
            }
            return false;
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(px(8), px(8), px(8), px(8));
        f.setLayoutParams(lp);
        return f;
    }

    // ---------- Veri ----------

    private void reload() {
        apps.clear();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName()) || apps.containsKey(p)) continue;
            apps.put(p, ri);
        }

        favs.clear();
        String saved = prefs.getString("favs", null);
        String[] list = saved == null ? DEFAULT_FAVS : TextUtils.split(saved, ",");
        for (String p : list) {
            if (apps.containsKey(p) && !favs.contains(p)) favs.add(p);
        }
        if (saved == null) saveFavs();

        render();
    }

    private void saveFavs() {
        prefs.edit().putString("favs", TextUtils.join(",", favs)).apply();
    }

    private void render() {
        String keep = focusPkg;
        favRow.removeAllViews();
        allBox.removeAllViews();

        int usable = getResources().getDisplayMetrics().widthPixels - 2 * px(SIDE);
        int fw = Math.round(usable / 4.5f) - px(16), fh = fw * 9 / 16;
        for (String p : favs) favRow.addView(card(p, fw, fh));
        if (favs.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("Henüz favori yok. Aşağıdan bir uygulamada MENÜ tuşuna basın.");
            t.setTextColor(0xFF8A93A0);
            t.setPadding(px(8), px(20), 0, px(20));
            favRow.addView(t);
        }

        ArrayList<String> rest = new ArrayList<>();
        for (String p : apps.keySet()) if (!favs.contains(p)) rest.add(p);
        final Collator col = Collator.getInstance(new Locale("tr", "TR"));
        final Map<String, String> labels = new HashMap<>();
        for (String p : rest) labels.put(p, apps.get(p).loadLabel(pm).toString());
        Collections.sort(rest, (a, b) -> col.compare(labels.get(a), labels.get(b)));

        allTitle.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        int gw = usable / COLS - px(16), gh = gw * 9 / 16;
        LinearLayout row = null;
        for (int k = 0; k < rest.size(); k++) {
            if (k % COLS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                allBox.addView(row);
            }
            row.addView(card(rest.get(k), gw, gh));
        }

        View target = keep == null ? null : findCard(keep);
        if (target == null && favRow.getChildCount() > 0) target = favRow.getChildAt(0);
        if (target != null) {
            final View t = target;
            t.post(t::requestFocus);
        }
    }

    private View findCard(String pkg) {
        View v = favRow.findViewWithTag(pkg);
        return v != null ? v : allBox.findViewWithTag(pkg);
    }

    // ---------- Eylemler ----------

    private void openApp(String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    private void launch(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private void showMenu(final String pkg) {
        ResolveInfo ri = apps.get(pkg);
        if (ri == null) return;
        final int idx = favs.indexOf(pkg);
        final List<String> items = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();

        if (idx < 0) {
            items.add("★  Favorilere ekle");
            acts.add(() -> favs.add(pkg));
        } else {
            if (idx > 0) {
                items.add("←  Sola taşı");
                acts.add(() -> Collections.swap(favs, idx, idx - 1));
                items.add("⇤  En başa taşı");
                acts.add(() -> { favs.remove(idx); favs.add(0, pkg); });
            }
            if (idx < favs.size() - 1) {
                items.add("→  Sağa taşı");
                acts.add(() -> Collections.swap(favs, idx, idx + 1));
            }
            items.add("✕  Favorilerden çıkar");
            acts.add(() -> favs.remove(pkg));
        }
        items.add("ⓘ  Uygulama bilgisi");
        acts.add(null);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(ri.loadLabel(pm))
                .setItems(items.toArray(new CharSequence[0]), (d, w) -> {
                    Runnable r = acts.get(w);
                    if (r == null) {
                        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        i.setData(android.net.Uri.parse("package:" + pkg));
                        launch(i);
                        return;
                    }
                    r.run();
                    saveFavs();
                    focusPkg = pkg;
                    render();
                })
                .show();
    }
}
