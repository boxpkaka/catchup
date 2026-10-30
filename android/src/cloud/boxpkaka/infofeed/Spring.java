package cloud.boxpkaka.infofeed;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;

/**
 * 弹簧插值：用刚度和阻尼比算出一条曲线，时长取停稳（离终点不到 0.3%）的时间。
 * 位置和形状用会轻微回弹的弹簧，颜色和透明度用不回弹的 FX。系统关闭动画时 ValueAnimator 直接跳到终点。
 * 跟手的东西松手时用 fling：从当前位置带着手指的速度弹过去，不会先停一下再走。
 */
final class Spring implements TimeInterpolator {
    /** 按压、开关、勾选。 */
    static final Spring FAST = new Spring(1400, 0.6);
    /** 卡片飞出、撤销条升起。 */
    static final Spring STD = new Spring(700, 0.75);
    /** 颜色、透明度。 */
    static final Spring FX = new Spring(900, 1);
    /** 翻页、推入推出、松手归位：接着手指的速度走，几乎不回弹。 */
    static final Spring PAGE = new Spring(380, 0.95);
    /** 卡片展开、收拢，面板弹出：比翻页软一点，到位时有一丝回弹。 */
    static final Spring CARD = new Spring(260, 0.84);

    interface Step {
        void at(float x);
    }

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
     * 带初速度弹过去：从 from 出发，初速度 velocity（每秒，和位置同单位，一般是像素），每帧把位置交给 step，
     * 离终点不到半个单位就停。返回的动画随时可以 cancel（手指接住时就这样停下来），cancel 掉的不调 end。
     */
    ValueAnimator fling(float from, float to, float velocity, Step step, Runnable end) {
        double a = from - to;
        double settle = 0;
        for (double t = 0; t < 3; t += 0.004) if (Math.abs(offset(a, velocity, t)) > 0.5) settle = t;
        long ms = Math.max(1, Math.round((settle + 0.004) * 1000));
        ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
        anim.setDuration(ms);
        anim.setInterpolator(null); // 线性：时间原样交给弹簧公式
        anim.addUpdateListener(x -> {
            float f = x.getAnimatedFraction();
            step.at(f >= 1 ? to : (float) (to + offset(a, velocity, f * ms / 1000.0)));
        });
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean canceled;

            @Override
            public void onAnimationCancel(Animator x) {
                canceled = true;
            }

            @Override
            public void onAnimationEnd(Animator x) {
                if (!canceled && end != null) end.run();
            }
        });
        anim.start();
        return anim;
    }

    /** 离终点还差多少：初始差 a、初速度 v 的阻尼振动在 t 秒时的位置。 */
    private double offset(double a, double v, double t) {
        if (z < 1) {
            double b = (v + z * w0 * a) / wd;
            return Math.exp(-z * w0 * t) * (a * Math.cos(wd * t) + b * Math.sin(wd * t));
        }
        return (a + (v + w0 * a) * t) * Math.exp(-w0 * t);
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
