package cloud.boxpkaka.infofeed;

import android.animation.ValueAnimator;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 流式回答逐字浮现：收到的字先藏着，按积压的多少调速往外放，网络一阵一阵地来，看起来也是匀速的；
 * 放出来的字从透明淡入，末尾总是一小段由浅到深的过渡。同一帧放出的字一起淡入，同时只挂十几个 span。
 * 来源角标是 ReplacementSpan，拿不到字的透明度，单独设它的 alpha。系统关闭动画时直接显示。
 */
final class Reveal implements Runnable {
    private static final float MIN_SPEED = 30; // 字/秒：积压很少或已经放完时的速度
    private static final float CATCH_UP = 8; // 每秒放掉积压的这么多倍，积压越多放得越快
    private static final long FADE = Spring.FX.duration;

    private final TextView view;
    private final ArrayDeque<long[]> fading = new ArrayDeque<>(); // 还在淡入的字：{起点, 终点, 放出的时间}
    private final List<Fade> spans = new ArrayList<>();
    private float shown; // 已经放出的字数
    private long last;
    private boolean running;

    Reveal(TextView view) {
        this.view = view;
    }

    /** 换成新的全文（之前的加上新收到的），已经放出的部分不受影响。 */
    void set(CharSequence text) {
        view.setText(text);
        spans.clear(); // 旧的 span 跟着旧文本一起换掉了
        if (!ValueAnimator.areAnimatorsEnabled()) {
            shown = text.length();
            return;
        }
        shown = Math.min(shown, text.length());
        apply(SystemClock.uptimeMillis());
        if (!running) {
            running = true;
            last = 0;
            view.postOnAnimation(this);
        }
    }

    @Override
    public void run() {
        long now = SystemClock.uptimeMillis();
        int len = view.getText().length();
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1000f);
        last = now;
        int from = (int) shown;
        shown = Math.min(len, shown + dt * Math.max(MIN_SPEED, (len - shown) * CATCH_UP));
        if ((int) shown > from) fading.add(new long[]{from, (int) shown, now});
        apply(now);
        if (((int) shown < len || !fading.isEmpty()) && view.isAttachedToWindow()) view.postOnAnimation(this);
        else running = false;
    }

    private void apply(long now) {
        Spannable sp = (Spannable) view.getText();
        int len = sp.length();
        for (Fade f : spans) sp.removeSpan(f);
        spans.clear();
        while (!fading.isEmpty() && now - fading.peek()[2] >= FADE) {
            long[] f = fading.poll();
            cites(sp, (int) Math.min(f[0], len), (int) Math.min(f[1], len), 1f);
        }
        for (long[] f : fading) {
            fade(sp, (int) Math.min(f[0], len), (int) Math.min(f[1], len),
                    Spring.FX.getInterpolation((now - f[2]) / (float) FADE));
        }
        fade(sp, (int) shown, len, 0f);
    }

    private void fade(Spannable sp, int start, int end, float alpha) {
        if (start >= end) return;
        Fade f = new Fade(alpha);
        sp.setSpan(f, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        spans.add(f);
        cites(sp, start, end, alpha);
    }

    /** 从这一段开始的角标；只碰到边的不算，不然前面已经显示完的角标会被设回透明。 */
    private static void cites(Spannable sp, int start, int end, float alpha) {
        for (Markdown.Cite c : sp.getSpans(start, end, Markdown.Cite.class)) {
            int s = sp.getSpanStart(c);
            if (s >= start && s < end) c.alpha = alpha;
        }
    }

    /** 字的透明度乘上 alpha；行内代码的底色也跟着淡入。 */
    private static final class Fade extends CharacterStyle implements UpdateAppearance {
        private final float alpha;

        Fade(float alpha) {
            this.alpha = alpha;
        }

        @Override
        public void updateDrawState(TextPaint p) {
            p.setAlpha(Math.round(p.getAlpha() * alpha));
            if (p.bgColor != 0) p.bgColor = (p.bgColor & 0xFFFFFF) | (Math.round((p.bgColor >>> 24) * alpha) << 24);
        }
    }
}
