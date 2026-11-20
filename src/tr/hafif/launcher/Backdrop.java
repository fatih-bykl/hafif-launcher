package tr.hafif.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * Ana ekranın arkasındaki sakin, hareketli duvar kâğıdı:
 * koyu zemin üzerinde birkaç büyük, yumuşak kenarlı gri ışık bulutu çok yavaş süzülür
 * (bir tur 1,5–2,5 dakika).
 *
 * Performans: Uygulama penceresinin dışında, ayrı bir yüzeyde (SurfaceView) ve ayrı bir
 * iş parçacığında çizilir; böylece duvar kâğıdı her güncellendiğinde ana ekrandaki kartlar
 * yeniden çizilmez. Yüzey 1/8 çözünürlükte (1080p'de 240×135) tutulur, büyütmeyi ekran
 * donanımı yapar. Saniyede ~8 kare; ana ekran görünmezken tamamen durur.
 */
final class Backdrop extends SurfaceView implements SurfaceHolder.Callback {

    private static final int BG = 0xFF0E1116;
    private static final long FRAME_MS = 125; // ~8 fps: bu kadar yavaş ve bulanık hareket için yeterli
    private static final int SCALE = 16; // 1080p'de 120×68 yüzey; büyütmeyi ekran donanımı yapar

    private static final class Blob {
        final int color;
        final float radius, cx, cy, ax, ay, px, py, phase;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Matrix m = new Matrix();

        Blob(int color, float radius, float cx, float cy, float ax, float ay, float px, float py, float phase) {
            this.color = color;
            this.radius = radius;
            this.cx = cx;
            this.cy = cy;
            this.ax = ax;
            this.ay = ay;
            this.px = px;
            this.py = py;
            this.phase = phase;
        }
    }

    // Gri tonlar, düşük saydamlık; biri çok hafif soğuk (mavimsi) gri
    private final Blob[] blobs = {
            new Blob(0x22B4BCC8, 0.95f, 0.22f, 0.30f, 0.16f, 0.12f, 97f, 131f, 0.0f),
            new Blob(0x1C9AA4B2, 1.10f, 0.78f, 0.68f, 0.18f, 0.14f, 123f, 89f, 1.7f),
            new Blob(0x168C9AB0, 0.80f, 0.55f, 0.15f, 0.20f, 0.10f, 151f, 109f, 3.1f),
            new Blob(0x14C8CCD2, 0.70f, 0.35f, 0.85f, 0.14f, 0.12f, 83f, 141f, 4.4f),
    };

    private final long t0 = SystemClock.uptimeMillis();
    private volatile boolean wanted;   // ana ekran görünür mü
    private volatile boolean hasSurface;
    private Thread thread;
    private int w, h;

    Backdrop(Context c) {
        super(c);
        SurfaceHolder holder = getHolder();
        holder.addCallback(this);
        // Küçük yüzey: donanım ekrana büyütür (bulutlar zaten bulanık)
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        w = Math.max(16, Math.max(dm.widthPixels, dm.heightPixels) / SCALE);
        h = Math.max(9, Math.min(dm.widthPixels, dm.heightPixels) / SCALE);
        holder.setFixedSize(w, h);
        holder.setFormat(android.graphics.PixelFormat.RGBA_8888); // 16 bit renkte halkalanma olmasın
        for (Blob b : blobs) {
            float r = b.radius * h;
            b.paint.setShader(new RadialGradient(0, 0, r,
                    new int[]{b.color, (b.color & 0x00FFFFFF) | ((b.color >>> 24) / 3 << 24), 0x00000000},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        }
    }

    void start() {
        wanted = true;
        startThreadIfReady();
    }

    void stop() {
        wanted = false;
        stopThread();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        hasSurface = true;
        startThreadIfReady();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        hasSurface = false;
        stopThread();
    }

    private synchronized void startThreadIfReady() {
        if (!wanted || !hasSurface || thread != null) return;
        thread = new Thread(this::loop, "duvar-kagidi");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private void stopThread() {
        Thread t;
        synchronized (this) {
            t = thread;
            thread = null;
        }
        if (t != null) {
            t.interrupt();
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void loop() {
        SurfaceHolder holder = getHolder();
        while (wanted && hasSurface && !Thread.currentThread().isInterrupted()) {
            long frameStart = SystemClock.uptimeMillis();
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c != null) drawFrame(c);
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    try {
                        holder.unlockCanvasAndPost(c);
                    } catch (Exception ignored) {
                    }
                }
            }
            long wait = FRAME_MS - (SystemClock.uptimeMillis() - frameStart);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void drawFrame(Canvas c) {
        c.drawColor(BG);
        float t = (SystemClock.uptimeMillis() - t0) / 1000f;
        for (Blob b : blobs) {
            double ux = 2 * Math.PI * t / b.px + b.phase;
            double uy = 2 * Math.PI * t / b.py + b.phase * 1.3;
            float x = (b.cx + b.ax * (float) Math.sin(ux)) * w;
            float y = (b.cy + b.ay * (float) Math.cos(uy)) * h;
            b.m.setTranslate(x, y);
            b.paint.getShader().setLocalMatrix(b.m);
            c.drawCircle(x, y, b.radius * h, b.paint);
        }
    }
}
