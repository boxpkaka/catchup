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
 * 每种形状只表达一件事：波浪是图标，太阳是重要性 5，四叶是重要性 4 和“未追踪信息”，软六边形是字标和要点；
 * 专题各有一种（见 Emblem），不和这些重复。
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

    /**
     * 专题的徽记：专题色的形状，中间是专题的图形（挖空成底色）。形状平时慢慢转，48 秒一圈，看不见或系统关闭了动画时不转。
     * 图形在 24×24 的格子里画，和设计稿的 SVG 一致。
     */
    static final class Emblem extends View {
        static final int WAVE = 0, SPARK = 1, ARROW = 2, TREND = 3, HEART = 4, UP = 5, YUAN = 6;
        private final Paint shape = new Paint(Paint.ANTI_ALIAS_FLAG), mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path(), glyphPath = new Path();
        private final Rect box = new Rect();
        private final float[] pts;
        private final int glyph;
        private ValueAnimator anim;
        private float spin;

        Emblem(Context c, float[] form, int glyph, int color, int hole) {
            super(c);
            pts = points(form);
            this.glyph = glyph;
            shape.setColor(color);
            mark.setColor(hole);
            mark.setStrokeCap(Paint.Cap.ROUND);
            mark.setStrokeJoin(Paint.Join.ROUND);
            buildGlyph();
        }

        private void buildGlyph() {
            Path p = glyphPath;
            switch (glyph) {
                case WAVE: // 四根高低不同的竖条
                    float[][] bars = {{3.5f, 9, 15}, {8, 5, 19}, {12.5f, 2.5f, 21.5f}, {17, 7, 17}};
                    for (float[] b : bars) p.addRoundRect(b[0], b[1], b[0] + 3, b[2], 1.5f, 1.5f, Path.Direction.CW);
                    break;
                case SPARK: // 四角星
                    p.moveTo(12, 1.5f);
                    p.cubicTo(12.7f, 7.1f, 15.9f, 10.9f, 22.5f, 12);
                    p.cubicTo(15.9f, 13.1f, 12.7f, 16.9f, 12, 22.5f);
                    p.cubicTo(11.3f, 16.9f, 8.1f, 13.1f, 1.5f, 12);
                    p.cubicTo(8.1f, 10.9f, 11.3f, 7.1f, 12, 1.5f);
                    p.close();
                    break;
                case ARROW: // 右上箭头，描边
                    p.moveTo(6, 18);
                    p.lineTo(18, 6);
                    p.moveTo(9, 6);
                    p.lineTo(18, 6);
                    p.lineTo(18, 15);
                    break;
                case TREND: // 上升的折线，描边
                    p.moveTo(3, 17.5f);
                    p.lineTo(9, 11.5f);
                    p.lineTo(13, 15.5f);
                    p.lineTo(21, 7.5f);
                    p.moveTo(15, 7.5f);
                    p.lineTo(21, 7.5f);
                    p.lineTo(21, 13.5f);
                    break;
                case HEART: // 两段 4.8 半径的圆弧做心形的上半
                    p.moveTo(12, 21);
                    p.cubicTo(12, 21, 3.5f, 15.8f, 3.5f, 9.8f);
                    p.arcTo(3.49f, 5.25f, 13.09f, 14.85f, 183, 137.6f, false);
                    p.arcTo(10.91f, 5.25f, 20.51f, 14.85f, 219.4f, 137.6f, false);
                    p.cubicTo(20.5f, 15.8f, 12, 21, 12, 21);
                    p.close();
                    break;
                case YUAN: // ¥，描边
                    p.moveTo(7, 4.5f);
                    p.lineTo(12, 11.5f);
                    p.lineTo(17, 4.5f);
                    p.moveTo(12, 11.5f);
                    p.lineTo(12, 20);
                    p.moveTo(7.5f, 12.5f);
                    p.lineTo(16.5f, 12.5f);
                    p.moveTo(7.5f, 16.5f);
                    p.lineTo(16.5f, 16.5f);
                    break;
                default: // UP：圆角三角形
                    p.moveTo(12, 4.5f);
                    p.lineTo(20.5f, 18.5f);
                    p.lineTo(3.5f, 18.5f);
                    p.close();
            }
            boolean stroke = glyph == ARROW || glyph == TREND || glyph == YUAN;
            mark.setStyle(stroke ? Paint.Style.STROKE : glyph == UP ? Paint.Style.FILL_AND_STROKE : Paint.Style.FILL);
            mark.setStrokeWidth(glyph == ARROW ? 3.2f : glyph == TREND || glyph == YUAN ? 3 : 2.4f);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            box.set(0, 0, w, h);
            trace(path, pts, pts, 0, box);
        }

        @Override
        public void onVisibilityAggregated(boolean visible) {
            super.onVisibilityAggregated(visible);
            if (visible && anim == null && ValueAnimator.areAnimatorsEnabled()) {
                anim = ValueAnimator.ofFloat(0, 360);
                anim.setDuration(48_000);
                anim.setRepeatCount(ValueAnimator.INFINITE);
                anim.setInterpolator(new LinearInterpolator());
                anim.addUpdateListener(a -> {
                    spin = (float) a.getAnimatedValue();
                    invalidate();
                });
                anim.start();
            } else if (!visible && anim != null) {
                anim.cancel();
                anim = null;
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            c.save();
            c.rotate(spin, box.exactCenterX(), box.exactCenterY());
            c.drawPath(path, shape);
            c.restore();
            float size = Math.min(box.width(), box.height()) * 0.46f;
            c.save();
            c.translate(box.exactCenterX() - size / 2, box.exactCenterY() - size / 2);
            c.scale(size / 24, size / 24);
            c.drawPath(glyphPath, mark);
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
