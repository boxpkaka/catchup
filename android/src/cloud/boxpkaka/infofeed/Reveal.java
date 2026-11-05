package cloud.boxpkaka.infofeed;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.MaskFilter;
import android.os.SystemClock;
import android.text.Layout;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;
import android.util.SparseArray;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 流式回答的浮现：收到的字先在后面攒着，放字的进度总比收到的晚一小会儿（LAG），按积压的多少平滑地调速，
 * 网络一阵一阵地来、中间卡一下，看起来也是匀匀地流出来。刚放出的字从透明、微微失焦慢慢变清楚，像墨落在纸上洇开再收住，
 * 末尾总是十几个字由浅到深、由虚到实的过渡。正文（Text）只长到已经放出的那一行，页面跟着一行一行长，不会先空出一截。
 * 同一帧放出的字一起变化，同时挂几十个 span。来源角标、代码块和表格的底这些自己画的装饰拿不到字的透明度，
 * 单独设（Markdown.Fading）。系统关闭动画时直接显示。
 */
final class Reveal implements Runnable {
    private static final float LAG = 0.4f; // 秒：放字比收字晚这么多，用来把一阵一阵的网络匀开
    private static final float MIN_SPEED = 24, MAX_SPEED = 400; // 字/秒
    private static final float EASE = 0.25f; // 秒：速度跟上积压要多久，免得忽快忽慢
    private static final long FADE = 560; // 一个字从出现到完全清楚
    private static final float BLUR = 2.5f; // dp：刚出现时有多虚

    private final Text view;
    private final float density;
    private final ArrayDeque<long[]> fading = new ArrayDeque<>(); // 还在浮现的字：{起点, 终点, 放出的时间}
    private final List<Fade> spans = new ArrayList<>();
    private final SparseArray<MaskFilter> blurs = new SparseArray<>(); // 按半个像素取整的半径，免得每帧新建
    private float shown, speed = MIN_SPEED; // 已经放出的字数，此刻每秒放几个字
    private long last;
    private boolean running;

    Reveal(Text view) {
        this.view = view;
        density = view.getResources().getDisplayMetrics().density;
    }

    /** 换成新的全文（之前的加上新收到的），已经放出的部分不受影响。 */
    void set(CharSequence text) {
        view.setText(text);
        spans.clear(); // 旧的 span 跟着旧文本一起换掉了
        if (!ValueAnimator.areAnimatorsEnabled()) {
            shown = text.length();
            view.upTo(-1);
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
        float target = Math.max(MIN_SPEED, Math.min(MAX_SPEED, (len - shown) / LAG));
        speed += (target - speed) * Math.min(1f, dt / EASE);
        int from = (int) shown;
        shown = Math.min(len, shown + dt * speed);
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
            marks(sp, (int) Math.min(f[0], len), (int) Math.min(f[1], len), 1f);
        }
        for (long[] f : fading) {
            float x = (now - f[2]) / (float) FADE, left = 1 - x;
            fade(sp, (int) Math.min(f[0], len), (int) Math.min(f[1], len), 1 - left * left, blur(BLUR * left * left * left));
        }
        fade(sp, (int) shown, len, 0f, null);
        view.upTo((int) shown);
    }

    private MaskFilter blur(float dp) {
        int half = Math.round(dp * density * 2);
        if (half < 1) return null;
        MaskFilter m = blurs.get(half);
        if (m == null) blurs.put(half, m = new BlurMaskFilter(half / 2f, BlurMaskFilter.Blur.NORMAL));
        return m;
    }

    private void fade(Spannable sp, int start, int end, float alpha, MaskFilter blur) {
        if (start >= end) return;
        Fade f = new Fade(alpha, blur);
        sp.setSpan(f, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        spans.add(f);
        marks(sp, start, end, alpha);
    }

    /** 从这一段开始的装饰（角标、代码块的底等）；只碰到边的不算，不然前面已经显示完的会被设回透明。 */
    private static void marks(Spannable sp, int start, int end, float alpha) {
        for (Markdown.Fading c : sp.getSpans(start, end, Markdown.Fading.class)) {
            int s = sp.getSpanStart(c);
            if (s >= start && s < end) c.fade(alpha);
        }
    }

    /** 字的透明度乘上 alpha，还没清楚的带一点模糊；行内代码的底色也跟着淡入。 */
    private static final class Fade extends CharacterStyle implements UpdateAppearance {
        private final float alpha;
        private final MaskFilter blur;

        Fade(float alpha, MaskFilter blur) {
            this.alpha = alpha;
            this.blur = blur;
        }

        @Override
        public void updateDrawState(TextPaint p) {
            p.setAlpha(Math.round(p.getAlpha() * alpha));
            if (blur != null) p.setMaskFilter(blur);
            if (p.bgColor != 0) p.bgColor = (p.bgColor & 0xFFFFFF) | (Math.round((p.bgColor >>> 24) * alpha) << 24);
        }
    }

    /**
     * 回答的正文：浮现时高度只到已经放出的那一行，后面还藏着的字不占地方；历史记录（upTo 为 -1）照常全部显示。
     * 底边按那一行的基线加上字的下沿和行距算，不管那一行是不是全文的最后一行都一样高，长的时候不会忽高忽低
     * （放完以后比照常显示多出一个行距）。
     */
    static final class Text extends TextView {
        private int upTo = -1;

        Text(Context c) {
            super(c);
        }

        void upTo(int n) {
            if (n == upTo) return;
            int before = line(upTo);
            upTo = n;
            if (line(n) != before) requestLayout();
        }

        private int line(int n) {
            Layout l = getLayout();
            return l == null || n < 0 ? -2 : n == 0 ? -1 : l.getLineForOffset(n - 1);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, heightSpec);
            Layout l = getLayout();
            if (upTo < 0 || l == null) return;
            int h = 0;
            if (upTo > 0) {
                h = l.getLineBaseline(l.getLineForOffset(upTo - 1)) + Math.round(getPaint().descent() + getLineSpacingExtra())
                        + getCompoundPaddingTop() + getCompoundPaddingBottom();
            }
            setMeasuredDimension(getMeasuredWidth(), h);
        }
    }
}
