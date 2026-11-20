package tr.hafif.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Üst çubuktaki internet hızı butonu.
 *  - 1. satır: anlık indirme / yükleme (tüm cihaz, TrafficStats ile, saniyede bir)
 *  - 2. satır: bağlantı türü ve Wi-Fi bağlantı hızı + son hız testi sonucu
 *  - OK'a basınca: ~8 sn indirme + ~8 sn yükleme testi (speed.cloudflare.com)
 */
final class NetMeter {

    private static final String TAG = "HafifHiz";
    // İndirme sunucuları sırayla denenir: biri reddederse (ör. Cloudflare "429 çok fazla istek") sonrakine geçilir.
    // Cloudflare büyük istekleri sık sık sınırladığı için 5 MB'lık parçalar istenir.
    private static final String[] DOWN_URLS = {
            "https://speed.cloudflare.com/__down?bytes=5000000",
            "https://proof.ovh.net/files/100Mb.dat",
            "https://nbg1-speed.hetzner.com/100MB.bin",
            "https://fsn1-speed.hetzner.com/100MB.bin",
    };
    private static final String UP_URL = "https://speed.cloudflare.com/__up";
    private static volatile int downIdx;
    private static volatile String lastError;
    private static final int STREAMS = 4;
    private static final long PHASE_MS = 8000;
    private static final int UP_CHUNK_BYTES = 8 * 1024 * 1024;

    private final Context ctx;
    final LinearLayout view;
    private final TextView live;
    private final TextView info;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private long lastRx = -1, lastTx = -1, lastT;
    private boolean running;
    private volatile boolean testing;
    private String lastTest;

    NetMeter(Context c, float dp) {
        ctx = c.getApplicationContext();
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        view.setFocusable(true);
        view.setClickable(true);
        int ph = Math.round(10 * dp), pv = Math.round(4 * dp);
        view.setPadding(ph, pv, ph, pv);

        live = new TextView(c);
        live.setTextColor(Color.WHITE);
        live.setTextSize(13);
        live.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        live.setMaxLines(1);
        live.setGravity(Gravity.END);
        live.setText("↓ —   ↑ —");
        view.addView(live, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        info = new TextView(c);
        info.setTextColor(0xFF9AA4B2);
        info.setTextSize(10);
        // 2 satır: "Wi-Fi 300 Mbps · IP 192.168.1.65" ve "Test ↓… ↑… Mbps"
        info.setMaxLines(2);
        info.setLineSpacing(0, 0.95f);
        info.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.setGravity(Gravity.END);
        view.addView(info, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Metin uzayıp kısaldıkça kutu zıplamasın ve kırpılmasın: en uzun metne göre sabit genişlik
        float w = Math.max(
                info.getPaint().measureText("Wi-Fi 1200 Mbps · IP 192.168.100.100"),
                live.getPaint().measureText("Test ↓ 1000 Mbps…  ↓ 99.9 MB/s"));
        info.setMinWidth(Math.round(w));
        live.setMinWidth(Math.round(w));

        view.setOnClickListener(v -> runTest());
        refreshInfo();
    }

    // ---------- Anlık hız ----------

    void start() {
        if (running) return;
        running = true;
        lastRx = -1;
        ui.post(tick);
    }

    void stop() {
        running = false;
        ui.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long rx = TrafficStats.getTotalRxBytes();
            long tx = TrafficStats.getTotalTxBytes();
            long now = SystemClock.elapsedRealtime();
            if (!testing) {
                if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) {
                    live.setText("↓ —   ↑ —");
                } else if (lastRx >= 0 && now > lastT) {
                    double sec = (now - lastT) / 1000.0;
                    live.setText("↓ " + rate((rx - lastRx) / sec) + "   ↑ " + rate((tx - lastTx) / sec));
                }
                refreshInfo();
            }
            lastRx = rx;
            lastTx = tx;
            lastT = now;
            ui.postDelayed(this, 1000);
        }
    };

    private static String rate(double bytesPerSec) {
        if (bytesPerSec < 0) bytesPerSec = 0;
        if (bytesPerSec >= 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSec / (1024 * 1024));
        }
        return String.format(Locale.ROOT, "%.0f KB/s", bytesPerSec / 1024);
    }

    @SuppressWarnings("deprecation")
    private String linkText() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return "Bağlantı yok";
            if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
                WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
                WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                int mbps = wi == null ? -1 : wi.getLinkSpeed();
                return mbps > 0 ? "Wi-Fi " + mbps + " Mbps" : "Wi-Fi";
            }
            if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) return "Kablolu";
            return ni.getTypeName();
        } catch (Exception e) {
            return "Ağ";
        }
    }

    private String netLine() {
        String ip = localIp();
        return ip == null ? linkText() : linkText() + " · IP " + ip;
    }

    /** Cihazın yerel IPv4 adresi (Wi-Fi/Ethernet öncelikli); izin gerektirmez. */
    private static String localIp() {
        try {
            String fallback = null;
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof java.net.Inet4Address) || a.isLoopbackAddress()) continue;
                    String name = ni.getName();
                    if (name.startsWith("wlan") || name.startsWith("eth")) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private void refreshInfo() {
        String t = lastTest != null ? "Test " + lastTest : "Hız testi için OK";
        info.setText(netLine() + "\n" + t);
    }

    // ---------- Hız testi ----------

    private void runTest() {
        if (testing) return;
        testing = true;
        live.setText("Hız testi başlıyor…");
        info.setText(netLine() + "\nLütfen bekleyin (~16 sn)");
        downIdx = 0;
        lastError = null;
        new Thread(() -> {
            double down = -1, up = -1;
            try {
                down = phase(true);
                up = phase(false);
            } catch (Exception e) {
                android.util.Log.w(TAG, "hız testi", e);
                lastError = e.getClass().getSimpleName();
            }
            final double d = down, u = up;
            final String err = lastError;
            ui.post(() -> {
                if (d <= 0 && u <= 0) {
                    lastTest = "başarısız" + (err != null ? " (" + err + ")" : "");
                    live.setText("Hız testi başarısız");
                } else {
                    lastTest = "↓" + mbps(d) + " ↑" + mbps(u) + " Mbps"
                            + ((d <= 0 || u <= 0) && err != null ? " (" + err + ")" : "");
                    live.setText("↓ " + mbps(d) + " Mbps   ↑ " + mbps(u) + " Mbps");
                }
                testing = false;
                lastRx = -1;
                refreshInfo();
            });
        }, "hiz-testi").start();
    }

    private static String mbps(double v) {
        if (v <= 0) return "—";
        return String.format(Locale.ROOT, v >= 100 ? "%.0f" : "%.1f", v);
    }

    /** Bir aşamayı (indirme ya da yükleme) STREAMS paralel bağlantıyla ölçer, Mbps döner. */
    private double phase(final boolean download) throws InterruptedException {
        final AtomicLong bytes = new AtomicLong();
        final long[] firstByteAt = {0};
        final long deadline = SystemClock.elapsedRealtime() + PHASE_MS;
        Thread[] th = new Thread[STREAMS];
        for (int i = 0; i < STREAMS; i++) {
            th[i] = new Thread(() -> {
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (download && downIdx >= DOWN_URLS.length) return; // tüm sunucular denendi
                    int idx = downIdx;
                    try {
                        if (download) downOnce(idx, bytes, firstByteAt, deadline);
                        else upOnce(bytes, firstByteAt, deadline);
                    } catch (Exception e) {
                        if (download) {
                            failServer(idx, e.getClass().getSimpleName());
                        } else {
                            android.util.Log.w(TAG, "yükleme hatası", e);
                            lastError = "yükleme: " + e.getClass().getSimpleName();
                            return;
                        }
                    }
                }
            });
            th[i].start();
        }
        final String label = download ? "↓" : "↑";
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(400);
            final double now = speed(bytes.get(), firstByteAt[0]);
            ui.post(() -> live.setText("Test " + label + " " + mbps(now) + " Mbps…"));
        }
        for (Thread t : th) t.join(3000);
        return speed(bytes.get(), firstByteAt[0]);
    }

    private static double speed(long bytes, long t0) {
        if (t0 == 0 || bytes == 0) return 0;
        double sec = (SystemClock.elapsedRealtime() - t0) / 1000.0;
        return sec <= 0 ? 0 : bytes * 8 / sec / 1_000_000.0;
    }

    private static void mark(long[] firstByteAt) {
        if (firstByteAt[0] == 0) {
            synchronized (firstByteAt) {
                if (firstByteAt[0] == 0) firstByteAt[0] = SystemClock.elapsedRealtime();
            }
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "HafifLauncher");
        return c;
    }

    /** Sunucu reddetti ya da hata verdi: sıradaki sunucuya geç (yalnızca bir kez, aynı sunucu için). */
    private static synchronized void failServer(int idx, String reason) {
        android.util.Log.w(TAG, "indirme sunucusu " + DOWN_URLS[idx] + " başarısız: " + reason);
        lastError = reason;
        if (downIdx == idx) downIdx = idx + 1;
    }

    private static void downOnce(int idx, AtomicLong bytes, long[] firstByteAt, long deadline) throws Exception {
        HttpURLConnection c = open(DOWN_URLS[idx]);
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            failServer(idx, "HTTP " + code);
            return;
        }
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                mark(firstByteAt);
                bytes.addAndGet(n);
                if (SystemClock.elapsedRealtime() >= deadline) break;
            }
        } finally {
            c.disconnect();
        }
    }

    private static void upOnce(AtomicLong bytes, long[] firstByteAt, long deadline) throws Exception {
        HttpURLConnection c = open(UP_URL);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.setFixedLengthStreamingMode(UP_CHUNK_BYTES);
        byte[] buf = new byte[64 * 1024];
        new Random().nextBytes(buf);
        try (OutputStream out = c.getOutputStream()) {
            int sent = 0;
            while (sent < UP_CHUNK_BYTES) {
                int n = Math.min(buf.length, UP_CHUNK_BYTES - sent);
                out.write(buf, 0, n);
                sent += n;
                mark(firstByteAt);
                bytes.addAndGet(n);
                if (SystemClock.elapsedRealtime() >= deadline) {
                    c.disconnect();
                    return;
                }
            }
            out.flush();
            c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }
}
