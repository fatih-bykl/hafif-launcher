package tr.hafif.launcher;

import android.content.Context;
import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

/**
 * Seçili öğeyi ekranın ortasına getiren kaydırma kapları.
 * Varsayılan davranış (öğeyi kenardan "görünür" yapmak) kapatılır; bunun yerine
 * odaklanan öğenin merkezi kabın merkezine yumuşakça kaydırılır.
 */
final class CenterScroll {

    private CenterScroll() {
    }

    /** Ok tuşları kabın kendi kaydırma mantığına değil, sistemin normal odak geçişine bırakılır. */
    static boolean isArrow(KeyEvent e) {
        int k = e.getKeyCode();
        return k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    /** Yatay: favoriler satırı. */
    static final class H extends HorizontalScrollView {
        private final Rect r = new Rect();

        H(Context c) {
            super(c);
            setSmoothScrollingEnabled(true);
        }

        @Override
        public boolean executeKeyEvent(KeyEvent e) {
            return !isArrow(e) && super.executeKeyEvent(e);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            return 0; // kenara dayalı otomatik kaydırma yok; ortalamayı biz yapıyoruz
        }

        @Override
        public void requestChildFocus(View child, View focused) {
            super.requestChildFocus(child, focused);
            if (focused == null || getWidth() == 0) return;
            focused.getDrawingRect(r);
            offsetDescendantRectToMyCoords(focused, r);
            smoothScrollTo(r.centerX() - getWidth() / 2, 0);
        }
    }

    /** Dikey: tüm sayfa (favoriler ve uygulama ızgarası satırları). */
    static final class V extends ScrollView {
        private final Rect r = new Rect();

        V(Context c) {
            super(c);
            setSmoothScrollingEnabled(true);
        }

        @Override
        public boolean executeKeyEvent(KeyEvent e) {
            return !isArrow(e) && super.executeKeyEvent(e);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            return 0;
        }

        @Override
        public void requestChildFocus(View child, View focused) {
            super.requestChildFocus(child, focused);
            if (focused == null || getHeight() == 0) return;
            focused.getDrawingRect(r);
            offsetDescendantRectToMyCoords(focused, r);
            smoothScrollTo(0, r.centerY() - getHeight() / 2);
        }
    }
}
