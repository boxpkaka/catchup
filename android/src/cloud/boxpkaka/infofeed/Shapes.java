package cloud.boxpkaka.infofeed;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

/**
 * 形状：都由 r(θ) = r0·(1 + amp·cos kθ) 取同样多的点生成，所以任意两个之间可以逐点插值变形。
 * 每种形状只表达一件事：波浪是图标，太阳是重要性 5，四叶是重要性 4 和“未追踪信息”，软六边形是字标和要点。
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
     * 加载指示器：在软六边形、波浪、太阳、四叶之间循环变形，同时转动。下拉刷新、首次加载、分享、对话里的“正在思考”都用它。
     * 系统关闭了动画时停在波浪形上不动。
     */
    static final class Loader extends View {
        private static final float[][] CYCLE = {points(SOFT), points(COOKIE), points(SUNNY), points(CLOVER)};
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Rect box = new Rect();
        private final PathInterpolator ease = new PathInterpolator(0.5f, 0, 0.2f, 1);
        private ValueAnimator anim;
        private float phase = 1; // 0～4：整数是某个形状，小数是两个形状之间
        private float spin;

        Loader(Context c, int color) {
            super(c);
            paint.setColor(color);
        }

        void start() {
            if (anim != null || !ValueAnimator.areAnimatorsEnabled()) return;
            anim = ValueAnimator.ofFloat(0, 4);
            anim.setDuration(3200);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                spin = a.getCurrentPlayTime() % 2000 * 0.18f; // 两秒一圈
                invalidate();
            });
            anim.start();
        }

        void stop() {
            if (anim != null) anim.cancel();
            anim = null;
            phase = 1;
            spin = 0;
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stop();
        }

        @Override
        protected void onDraw(Canvas c) {
            int from = (int) phase % 4;
            float t = ease.getInterpolation(phase - (int) phase);
            box.set(getPaddingLeft(), getPaddingTop(), getWidth() - getPaddingRight(), getHeight() - getPaddingBottom());
            trace(path, CYCLE[from], CYCLE[(from + 1) % 4], t, box);
            c.save();
            c.rotate(spin, box.exactCenterX(), box.exactCenterY());
            c.drawPath(path, paint);
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
