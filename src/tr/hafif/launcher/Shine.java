package tr.hafif.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/**
 * Kartın üzerinden soldan sağa çapraz geçen ışık şeridi (bir kez).
 * View'in üst katmanına (overlay) eklenir; kartın yuvarlak köşelerine göre kırpılır.
 */
final class Shine extends Drawable {

    private static final long DURATION_MS = 1100;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final Matrix m = new Matrix();
    private final float radius;
    private LinearGradient shader;
    private float progress;

    private Shine(float radius) {
        this.radius = radius;
    }

    /** Verilen kartın üzerinden bir ışık geçirir. */
    static void play(final View v, float radius) {
        if (v.getWidth() == 0) return;
        final Shine s = new Shine(radius);
        s.setBounds(0, 0, v.getWidth(), v.getHeight());
        v.getOverlay().add(s);
        // Sistemin animasyon ölçeğinden bağımsız (ayar 0 olsa da ışık geçer)
        Anim.replace(v, R_SHINE, DURATION_MS, new AccelerateDecelerateInterpolator(), t -> {
            s.progress = t;
            s.invalidateSelf();
        }, () -> v.getOverlay().remove(s));
        v.setTag(R_SHINE_DRAWABLE, s);
    }

    /** Kartta süren ışık animasyonu varsa durdurur. */
    static void stop(View v) {
        Object a = v.getTag(R_SHINE);
        if (a instanceof Anim.Handle) ((Anim.Handle) a).cancel();
        Object d = v.getTag(R_SHINE_DRAWABLE);
        if (d instanceof Drawable) v.getOverlay().remove((Drawable) d);
        v.setTag(R_SHINE, null);
        v.setTag(R_SHINE_DRAWABLE, null);
    }

    // setTag(key) için uygulamaya özgü sabit bir anahtar (kaynak kimliği biçiminde)
    static final int R_SHINE = 0x7f0a5150;
    static final int R_SHINE_DRAWABLE = 0x7f0a5152;

    @Override
    protected void onBoundsChange(android.graphics.Rect b) {
        float w = b.width();
        // Işık şeridi: genişliğin ~%35'i. Kenarlarda hafif mavi ton (beyaz kartlarda da görünsün),
        // ortada beyaza yakın parlak çekirdek; uçlar saydam.
        shader = new LinearGradient(0, 0, w * 0.35f, 0,
                new int[]{0x004FA8FF, 0x404FA8FF, 0x8CBFE6FF, 0xB3F2FAFF, 0x8CBFE6FF, 0x404FA8FF, 0x004FA8FF},
                new float[]{0f, 0.22f, 0.42f, 0.5f, 0.58f, 0.78f, 1f}, Shader.TileMode.CLAMP);
        paint.setShader(shader);
        rect.set(b);
        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
    }

    @Override
    public void draw(Canvas c) {
        float w = rect.width(), h = rect.height();
        float band = w * 0.35f;
        // şerit soldan (tamamen dışarıda) sağa (tamamen dışarıda) gider, 20° eğik
        float x = -band - h * 0.4f + (w + band + h * 0.8f) * progress;
        m.reset();
        m.setRotate(20f);
        m.postTranslate(x, 0);
        shader.setLocalMatrix(m);
        int save = c.save();
        c.clipPath(clip);
        c.drawRect(rect, paint);
        c.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
