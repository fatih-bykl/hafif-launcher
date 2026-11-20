package tr.hafif.launcher;

import android.animation.TimeInterpolator;
import android.os.SystemClock;
import android.view.View;

/**
 * Sistemin "Animatör süre ölçeği" ayarından bağımsız küçük animasyon zamanlayıcısı.
 * Android'in ValueAnimator/ObjectAnimator/ViewPropertyAnimator sınıfları bu ayar 0 iken
 * animasyonu atlayıp doğrudan sona geçer; burada süre gerçek saatle ölçülür ve her kare
 * View.postOnAnimation ile ekran yenilemesine bağlı olarak çizilir.
 */
final class Anim {

    interface Update {
        void on(float t); // t: 0..1 (interpolator uygulanmış)
    }

    /** Çalışan animasyonu durdurmak için tutamak. */
    static final class Handle {
        private boolean cancelled;

        void cancel() {
            cancelled = true;
        }
    }

    private Anim() {
    }

    static Handle run(final View host, final long durationMs, final TimeInterpolator interp,
                      final Update update, final Runnable end) {
        final Handle h = new Handle();
        final long start = SystemClock.uptimeMillis();
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (h.cancelled) return;
            float t = durationMs <= 0 ? 1f
                    : Math.min(1f, (SystemClock.uptimeMillis() - start) / (float) durationMs);
            update.on(interp == null ? t : interp.getInterpolation(t));
            if (t < 1f) {
                host.postOnAnimation(step[0]);
            } else if (end != null) {
                end.run();
            }
        };
        step[0].run();
        return h;
    }

    /** View üzerinde, aynı anahtarla önceki animasyonu iptal edip yenisini başlatır. */
    static Handle replace(View host, int tagKey, long durationMs, TimeInterpolator interp,
                          Update update, Runnable end) {
        Object prev = host.getTag(tagKey);
        if (prev instanceof Handle) ((Handle) prev).cancel();
        Handle h = run(host, durationMs, interp, update, end);
        host.setTag(tagKey, h);
        return h;
    }
}
