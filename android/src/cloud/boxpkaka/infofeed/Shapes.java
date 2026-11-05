package cloud.boxpkaka.infofeed;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

/**
 * 形状：都由 r(θ) = r0·(1 + amp·cos kθ) 取同样多的点生成，所以任意两个之间可以逐点插值变形。
 * 每种形状只表达一件事：波浪是图标，太阳是重要性 5，四叶是重要性 4 和“未追踪信息”，软六边形是字标和要点。
 * 加载时不用形状：有占位的让磨砂占位明暗起伏（breathe），没有的是一团呼吸的光（Loader）。
 */
final class Shapes {
    static final float[] COOKIE = {12, 0.075f}, SUNNY = {8, 0.1f}, CLOVER = {4, 0.2f}, SOFT = {6, 0.09f};
    private static final int N = 180;

    private Shapes() {}

    /** 单位方框 [0,1] 里的轮廓点，x、y 交替；从正上方开始顺时针。 */
    static float[] points(float[] shape) {
        float k = shape[0], amp = shape[1], r = 0.5f / (1 + amp);
        float[] p = new float[N * 2];
        for (int i = 0; i < N; i++) {
            double a = i * 2 * Math.PI / N, rr = r * (1 + amp * Math.cos(k * a));
            p[i * 2] = (float) (0.5 + rr * Math.cos(a - Math.PI / 2));
            p[i * 2 + 1] = (float) (0.5 + rr * Math.sin(a - Math.PI / 2));
        }
        return p;
    }

    private static void trace(Path path, float[] p, float[] q, float t, Rect b) {
        path.rewind();
        float w = b.width(), h = b.height();
        for (int i = 0; i < N; i++) {
            float x = p[i * 2] + (q[i * 2] - p[i * 2]) * t, y = p[i * 2 + 1] + (q[i * 2 + 1] - p[i * 2 + 1]) * t;
            if (i == 0) path.moveTo(b.left + x * w, b.top + y * h);
            else path.lineTo(b.left + x * w, b.top + y * h);
        }
        path.close();
    }

    /** 填色的形状，铺满自己的边界；可以放在 TextView 背后，在形状中间写数字。 */
    static final class Blob extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float[] pts;

        Blob(float[] shape, int color) {
            pts = points(shape);
            paint.setColor(color);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            trace(path, pts, pts, 0, b);
        }

        @Override
        public void draw(Canvas c) {
            c.drawPath(path, paint);
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

    /**
     * 加载时的磨砂占位：rows 里每一块的底慢慢明暗起伏，一块比一块晚一点，像一道很宽的光从磨砂后面缓缓往下移过，
     * 不转圈、不抢眼。进窗口时开始、离开时停（也可以拿返回的动画提前停），系统关闭了动画时不动。
     * 每一块要有自己的背景（t.frost、t.segments 给的都是）；没有背景的一行（并排的几块）里面几块一起起伏。
     */
    static ValueAnimator breathe(ViewGroup rows) {
        ValueAnimator glow = ValueAnimator.ofFloat(0, 1);
        glow.setDuration(2600);
        glow.setRepeatCount(ValueAnimator.INFINITE);
        glow.setInterpolator(new LinearInterpolator());
        glow.addUpdateListener(a -> {
            float p = (float) a.getAnimatedValue();
            for (int i = 0; i < rows.getChildCount(); i++) {
                shade(rows.getChildAt(i), Math.round(255 * (0.6f + 0.22f * (float) Math.sin(2 * Math.PI * (p - i * 0.08f)))));
            }
        });
        rows.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                if (ValueAnimator.areAnimatorsEnabled()) glow.start();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                glow.cancel();
            }
        });
        if (rows.isAttachedToWindow() && ValueAnimator.areAnimatorsEnabled()) glow.start(); // 已经在屏幕上（比如重试）
        return glow;
    }

    private static void shade(View v, int alpha) {
        if (v.getBackground() != null) v.getBackground().setAlpha(alpha);
        else if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) shade(((ViewGroup) v).getChildAt(i), alpha);
    }

    /**
     * 加载指示器：一团柔和的光，中间亮、往外化开，慢慢亮起来又暗下去，像待机时的呼吸灯，不转、不变形。
     * 没有占位可以起伏的地方用它：下拉刷新、分享、对话里的“正在思考”。系统关闭了动画时一直亮着。
     */
    static final class Loader extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;
        private ValueAnimator anim;
        private float breath = 1; // 0 暗、1 亮

        Loader(Context c, int color) {
            super(c);
            this.color = color;
        }

        void start() {
            if (anim != null || !ValueAnimator.areAnimatorsEnabled()) return;
            anim = ValueAnimator.ofFloat(0, 1);
            anim.setDuration(2400);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> {
                breath = 0.5f + 0.5f * (float) Math.cos(2 * Math.PI * (float) a.getAnimatedValue()); // 从亮开始
                invalidate();
            });
            anim.start();
        }

        void stop() {
            if (anim != null) anim.cancel();
            anim = null;
            breath = 1;
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stop();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float cx = (getPaddingLeft() + w - getPaddingRight()) / 2f, cy = (getPaddingTop() + h - getPaddingBottom()) / 2f;
            float r = Math.max(1, Math.min(w - getPaddingLeft() - getPaddingRight(), h - getPaddingTop() - getPaddingBottom()) / 2f);
            paint.setShader(new RadialGradient(cx, cy, r, new int[]{color, color & 0xFFFFFF | 0x8C000000,
                    color & 0xFFFFFF | 0x26000000, color & 0xFFFFFF}, new float[]{0, 0.28f, 0.62f, 1}, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = (getPaddingLeft() + getWidth() - getPaddingRight()) / 2f;
            float cy = (getPaddingTop() + getHeight() - getPaddingBottom()) / 2f;
            float r = Math.min(getWidth() - getPaddingLeft() - getPaddingRight(), getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
            float s = 0.72f + 0.28f * breath; // 亮的时候光也化得开一点
            paint.setAlpha(Math.round(255 * (0.4f + 0.6f * breath)));
            c.save();
            c.scale(s, s, cx, cy);
            c.drawCircle(cx, cy, r, paint);
            c.restore();
        }
    }

    /** 撤销倒计时：一条波浪线在给定时间内从右往左走完。比数秒更安静，也看得出还剩多少。 */
    static final class Wave extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private ValueAnimator anim;
        private float left = 1;

        Wave(Context c, int color) {
            super(c);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        /** 从满格开始倒数；系统关闭了动画时一直是满的。 */
        void countdown(long ms) {
            stop();
            left = 1;
            invalidate();
            if (!ValueAnimator.areAnimatorsEnabled()) return;
            anim = ValueAnimator.ofFloat(1, 0);
            anim.setDuration(ms);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> {
                left = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        void stop() {
            if (anim != null) anim.cancel();
            anim = null;
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stop();
        }

        @Override
        protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density, stroke = 3 * d, amp = 3.2f * d;
            float mid = getHeight() / 2f, end = (getWidth() - stroke) * left;
            if (end <= 0) return;
            paint.setStrokeWidth(stroke);
            path.rewind();
            for (float x = 0; x <= end; x += d) {
                float y = mid + amp * (float) Math.sin(x / (4.2f * d));
                if (x == 0) path.moveTo(stroke / 2 + x, y);
                else path.lineTo(stroke / 2 + x, y);
            }
            c.drawPath(path, paint);
        }
    }
}
