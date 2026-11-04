package cloud.boxpkaka.infofeed;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;

/**
 * 弹簧插值：用刚度和阻尼比算出一条曲线，时长取停稳（离终点不到 0.3%）的时间。按用途的几条弹簧由风格给（见 Motion）。
 * 系统关闭动画时 ValueAnimator 直接跳到终点。
 * 跟手的东西松手时用 fling：从当前位置带着手指的速度弹过去，不会先停一下再走。
 */
final class Spring implements TimeInterpolator {
    interface Step {
        void at(float x);
    }

    final long duration;
    private final double w0, z, wd, end;

    Spring(double stiffness, double damping) {
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
        private Theme.Finish finish, sheenFinish;
        private float sheen; // 金属的程度：0 平涂，1 金属（见 Theme.shine）
        private final Theme.Glow glow = new Theme.Glow();
        private android.graphics.RectF layer;

        Shape(int color, float radiusPx, float pressedRadiusPx) {
            this.color = color;
            radius = radiusPx;
            pressedRadius = pressedRadiusPx;
            setColor(color);
            setCornerRadius(radiusPx);
        }

        /** 磨砂的质感（倒角），见 Theme.Finish；变成金属时淡掉。 */
        Shape finish(Theme.Finish f) {
            finish = f;
            return this;
        }

        /** 金属的质感：跟着金属的程度显出来，平涂时没有。 */
        Shape sheenFinish(Theme.Finish f) {
            sheenFinish = f;
            return this;
        }

        /** 涂成以 base 为中间色的金属，见 Theme.shine。 */
        Shape metal(int base) {
            color = base;
            sheen = 1;
            Theme.shine(this, glow, base, 1);
            return this;
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            super.draw(c);
            if (sheen > 0) glow.draw(c, getBounds(), Theme.radii(this));
            if (finish != null && sheen < 1) { // 磨砂的倒角：变成金属时跟着淡掉
                if (sheen <= 0) {
                    finish.draw(c, getBounds(), Theme.radii(this));
                } else {
                    if (layer == null) layer = new android.graphics.RectF();
                    layer.set(getBounds());
                    int save = c.saveLayerAlpha(layer, Math.round(255 * (1 - sheen)));
                    finish.draw(c, getBounds(), Theme.radii(this));
                    c.restoreToCount(save);
                }
            }
            if (sheenFinish == null || sheen <= 0) return;
            if (sheen >= 1) {
                sheenFinish.draw(c, getBounds(), Theme.radii(this));
                return;
            }
            if (layer == null) layer = new android.graphics.RectF();
            layer.set(getBounds());
            int save = c.saveLayerAlpha(layer, Math.round(255 * sheen));
            sheenFinish.draw(c, getBounds(), Theme.radii(this));
            c.restoreToCount(save);
        }

        float radius() {
            return radius;
        }

        /** 弹到新的圆角和颜色（平涂）。 */
        void morph(float toRadius, int toColor) {
            morph(toRadius, toColor, false);
        }

        /** 弹到新的圆角和颜色；metal 为 true 时变成以 toColor 为中间色的金属，颜色和金属感一起慢慢变。 */
        void morph(float toRadius, int toColor, boolean metal) {
            radius = toRadius;
            animateRadius(toRadius);
            if (tint != null) tint.cancel();
            float toSheen = metal ? 1 : 0;
            if (toColor == color && toSheen == sheen) return;
            int fromColor = color;
            float fromSheen = sheen;
            ArgbEvaluator argb = new ArgbEvaluator();
            tint = ValueAnimator.ofFloat(0, 1);
            Spring fx = Motion.now().fx;
            tint.setDuration(fx.duration);
            tint.setInterpolator(fx);
            tint.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                int c = (int) argb.evaluate(f, fromColor, toColor);
                sheen = fromSheen + (toSheen - fromSheen) * f;
                if (sheen > 0) Theme.shine(this, glow, c, sheen);
                else setColor(c);
                invalidateSelf();
            });
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
            Spring fast = Motion.now().fast;
            shape.setDuration(fast.duration);
            shape.setInterpolator(fast);
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
