package cloud.boxpkaka.infofeed;

import android.animation.ArgbEvaluator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;

/**
 * 弹簧插值：用刚度和阻尼比算出一条曲线，时长取停稳（离终点不到 0.3%）的时间。
 * 位置和形状用会轻微回弹的弹簧，颜色和透明度用不回弹的 FX。系统关闭动画时 ValueAnimator 直接跳到终点。
 */
final class Spring implements TimeInterpolator {
    /** 按压、开关、勾选。 */
    static final Spring FAST = new Spring(1400, 0.6);
    /** 卡片飞出、撤销条升起。 */
    static final Spring STD = new Spring(700, 0.75);
    /** 颜色、透明度。 */
    static final Spring FX = new Spring(900, 1);

    final long duration;
    private final double w0, z, wd, end;

    private Spring(double stiffness, double damping) {
        w0 = Math.sqrt(stiffness);
        z = damping;
        wd = w0 * Math.sqrt(Math.max(1e-6, 1 - z * z));
        double settle = 0;
        for (double t = 0; t < 4; t += 0.002) if (Math.abs(1 - at(t)) > 0.003) settle = t;
        end = settle + 0.01;
        duration = Math.round(end * 1000);
    }

    private double at(double t) {
        if (z < 1) return 1 - Math.exp(-z * w0 * t) * (Math.cos(wd * t) + z * w0 / wd * Math.sin(wd * t));
        return 1 - Math.exp(-w0 * t) * (1 + w0 * t);
    }

    @Override
    public float getInterpolation(float x) {
        return x >= 1 ? 1 : (float) at(x * end);
    }

    /**
     * 会变形的圆角背景：可以用弹簧改圆角和颜色；pressedRadius 不小于 0 时，按下收成这个圆角、松手弹回。
     * 放进 RippleDrawable 当内容层、不给遮罩，水波纹就跟着形状走。
     */
    static final class Shape extends GradientDrawable {
        private final float pressedRadius;
        private float radius;
        private int color;
        private boolean pressed;
        private ValueAnimator shape, tint;

        Shape(int color, float radiusPx, float pressedRadiusPx) {
            this.color = color;
            radius = radiusPx;
            pressedRadius = pressedRadiusPx;
            setColor(color);
            setCornerRadius(radiusPx);
        }

        float radius() {
            return radius;
        }

        /** 弹到新的圆角和颜色。 */
        void morph(float toRadius, int toColor) {
            radius = toRadius;
            animateRadius(toRadius);
            if (tint != null) tint.cancel();
            if (toColor == color) return;
            tint = ValueAnimator.ofObject(new ArgbEvaluator(), color, toColor);
            tint.setDuration(FX.duration);
            tint.setInterpolator(FX);
            tint.addUpdateListener(a -> setColor((int) a.getAnimatedValue()));
            tint.start();
            color = toColor;
        }

        /** 胶囊的圆角写成 99dp，实际只有高度的一半；从实际值开始变，动画才不会前半段看不出变化。 */
        private float visible(float r) {
            int w = getBounds().width(), h = getBounds().height();
            return w > 0 && h > 0 ? Math.min(r, Math.min(w, h) / 2f) : r;
        }

        private void animateRadius(float to) {
            if (shape != null) shape.cancel();
            shape = ValueAnimator.ofFloat(visible(getCornerRadius()), visible(to));
            shape.setDuration(FAST.duration);
            shape.setInterpolator(FAST);
            shape.addUpdateListener(a -> setCornerRadius(Math.max(0, (float) a.getAnimatedValue())));
            shape.start();
        }

        @Override
        public boolean isStateful() {
            return pressedRadius >= 0 || super.isStateful();
        }

        @Override
        protected boolean onStateChange(int[] state) {
            boolean super_ = super.onStateChange(state);
            if (pressedRadius < 0) return super_;
            boolean now = false;
            for (int s : state) if (s == android.R.attr.state_pressed) now = true;
            if (now == pressed) return super_;
            pressed = now;
            animateRadius(now ? pressedRadius : radius);
            return true;
        }
    }
}
