package tr.hafif.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.util.Locale;

/**
 * İbreli hız göstergesi (Mbps). Ölçek kademeli: 0, 5, 10, 50, 100, 250, 500, 750, 1000
 * kademeleri yay üzerinde eşit aralıklıdır; ibre kademeler arasında doğrusal ilerler.
 * İbre hedef değere yumuşakça kayar.
 */
final class GaugeView extends View {

    private static final float[] STEPS = {0, 5, 10, 50, 100, 250, 500, 750, 1000};
    private static final String[] LABELS = {"0", "5", "10", "50", "100", "250", "500", "750", "1000"};
    private static final float START = 135f;   // yayın başladığı açı (sol alt)
    private static final float SWEEP = 270f;   // toplam yay açısı

    private final float dp;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Path needlePath = new Path();

    private float target, shown;   // Mbps
    private long lastFrame;

    GaugeView(Context c, float dp) {
        super(c);
        this.dp = dp;

        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(0xFF2A3140);

        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);

        label.setColor(0xFFC9D1DC);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        needle.setStyle(Paint.Style.FILL);

        hub.setColor(0xFF0E1116);

        value.setColor(Color.WHITE);
        value.setTextAlign(Paint.Align.CENTER);
        value.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));

        unit.setColor(0xFF4FD8C4);
        unit.setTextAlign(Paint.Align.CENTER);
        unit.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    /** İbrenin gideceği değer (Mbps). */
    void setMbps(float mbps) {
        if (mbps < 0 || Float.isNaN(mbps)) mbps = 0;
        target = Math.min(mbps, STEPS[STEPS.length - 1]);
        if (lastFrame == 0) lastFrame = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    /** Değeri 0..1 aralığında yay üzerindeki konuma çevirir (kademeli ölçek). */
    private static float fraction(float v) {
        for (int i = 1; i < STEPS.length; i++) {
            if (v <= STEPS[i]) {
                float seg = (v - STEPS[i - 1]) / (STEPS[i] - STEPS[i - 1]);
                return ((i - 1) + seg) / (STEPS.length - 1);
            }
        }
        return 1f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float stroke = Math.min(w, h) * 0.11f;
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);
        float pad = stroke / 2 + 1;
        float size = Math.min(w, h) - 2 * pad;
        float cx = w / 2f;
        float cy = pad + size / 2;
        oval.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);

        // Mavi → turkuaz → yeşil renk geçişi, yayın başından sonuna
        SweepGradient g = new SweepGradient(cx, cy,
                new int[]{0xFF7CFF6B, 0xFF00B4FF, 0xFF00B4FF, 0xFF36E2D2, 0xFF7CFF6B},
                new float[]{0f, START / 360f - 0.001f, START / 360f, (START + SWEEP * 0.55f) / 360f, 1f});
        Matrix m = new Matrix();
        g.setLocalMatrix(m);
        arc.setShader(g);

        // Yazılar göstergenin boyutuyla orantılı: küçük hâlde de küçülür, üst üste binmez
        label.setTextSize(size * 0.068f);
        value.setTextSize(size * 0.19f);
        unit.setTextSize(size * 0.085f);
    }

    @Override
    protected void onDraw(Canvas c) {
        // ibreyi hedefe doğru yumuşakça kaydır
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float k = 1f - (float) Math.exp(-dt * 8f);
        shown += (target - shown) * k;
        boolean moving = Math.abs(target - shown) > 0.05f;
        if (!moving) shown = target;

        float cx = oval.centerX(), cy = oval.centerY(), r = oval.width() / 2;
        float frac = fraction(shown);

        c.drawArc(oval, START, SWEEP, false, track);
        if (frac > 0.002f) c.drawArc(oval, START, SWEEP * frac, false, arc);

        // ölçek yazıları: örnekteki gibi tüm kademeler, yayın hemen iç kenarında eşit aralıkla
        // (0 sol altta, 100 tepede, 1000 sağ altta)
        float lr = r - arc.getStrokeWidth() * 0.5f - label.getTextSize() * 1.15f;
        float ty = -(label.ascent() + label.descent()) / 2;
        for (int i = 0; i < STEPS.length; i++) {
            double a = Math.toRadians(START + SWEEP * i / (STEPS.length - 1));
            c.drawText(LABELS[i], cx + (float) Math.cos(a) * lr, cy + (float) Math.sin(a) * lr + ty, label);
        }

        // ibre: merkezden yaya doğru incelen üçgen
        double a = Math.toRadians(START + SWEEP * frac);
        float len = r - arc.getStrokeWidth() * 0.3f;
        float base = r * 0.08f;
        float tx = cx + (float) Math.cos(a) * len, tyy = cy + (float) Math.sin(a) * len;
        float px = (float) -Math.sin(a) * base, py = (float) Math.cos(a) * base;
        needlePath.reset();
        needlePath.moveTo(cx + px, cy + py);
        needlePath.lineTo(tx, tyy);
        needlePath.lineTo(cx - px, cy - py);
        needlePath.close();
        needle.setShader(new LinearGradient(cx, cy, tx, tyy, 0x33FFFFFF, 0xFFFFFFFF, Shader.TileMode.CLAMP));
        c.drawPath(needlePath, needle);
        c.drawCircle(cx, cy, base * 1.2f, hub);

        // değer ve birim (alt boşlukta)
        // değer: alttaki açıklıkta, 0 ile 1000 yazılarının arasında (örnekteki gibi)
        float vy = cy + r * 0.74f;
        c.drawText(fmt(shown), cx, vy, value);
        c.drawText("Mbps", cx, vy + unit.getTextSize() * 1.1f, unit);

        if (moving) postInvalidateOnAnimation();
    }

    private static String fmt(float v) {
        if (v >= 100) return String.format(Locale.ROOT, "%.0f", v);
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
