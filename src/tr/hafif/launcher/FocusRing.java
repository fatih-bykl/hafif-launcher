package tr.hafif.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Seçili öğenin çevresindeki 3B görünümlü mavi çerçeve:
 *  1) dışarı doğru sönen mavi parıltı (hale),
 *  2) üstte açık turkuaz, altta koyu mavi geçişli kalın şerit (eğim/bevel hissi),
 *  3) şeridin iç kenarında üstten aşağı sönen ince beyaz parlama çizgisi.
 * Parıltı öğenin sınırlarının dışına çizilir; ebeveynlerde clipChildren=false olmalıdır.
 */
final class FocusRing extends Drawable {

    private final float radius, stroke, glow;
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    FocusRing(float radius, float dp) {
        this.radius = radius;
        this.stroke = 4.5f * dp;
        this.glow = 9f * dp;
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(stroke);
        glowPaint.setStyle(Paint.Style.STROKE);
        shinePaint.setStyle(Paint.Style.STROKE);
        shinePaint.setStrokeWidth(Math.max(1f, 1.3f * dp));
    }

    @Override
    protected void onBoundsChange(android.graphics.Rect b) {
        // üst: açık turkuaz, orta: canlı mavi, alt: koyu mavi → yukarıdan ışık alan 3B şerit
        ringPaint.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                new int[]{0xFF9BE7FF, 0xFF2D8CFF, 0xFF1446C8},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        shinePaint.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.6f,
                0xB3FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();

        // 1) parıltı: dışa doğru sönen birkaç ince halka
        int steps = 6;
        for (int i = steps; i >= 1; i--) {
            float d = stroke / 2 + glow * i / steps;
            int a = Math.round(70f * (1f - (float) (i - 1) / steps) * (1f - (float) (i - 1) / steps));
            glowPaint.setColor((a << 24) | 0x2D8CFF);
            glowPaint.setStrokeWidth(glow / steps + 1);
            r.set(b.left - d, b.top - d, b.right + d, b.bottom + d);
            c.drawRoundRect(r, radius + d, radius + d, glowPaint);
        }

        // 2) ana şerit: kartın kenarından hafifçe dışarıda
        float o = stroke / 2 - stroke * 0.25f;
        r.set(b.left - o, b.top - o, b.right + o, b.bottom + o);
        c.drawRoundRect(r, radius + o, radius + o, ringPaint);

        // 3) iç parlama çizgisi
        float in = stroke / 2 + shinePaint.getStrokeWidth() / 2 - o;
        r.set(b.left - o + in, b.top - o + in, b.right + o - in, b.bottom + o - in);
        float ri = Math.max(0, radius + o - in);
        c.drawRoundRect(r, ri, ri, shinePaint);
    }

    @Override
    public void setAlpha(int alpha) {
        ringPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        ringPaint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
