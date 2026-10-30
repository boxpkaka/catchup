package cloud.boxpkaka.infofeed;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 像 iOS 的导航：页面从右边推进来，下面那页往左退三成、压暗；右滑时整页跟手，下面那页跟着回来，松手带着手指的速度
 * 滑出去（返回）或弹回，弹回的途中按住还能接着拖。返回键、返回按钮和代码里的 finish() 也走同一套滑出。
 * 能看到下面那页是因为这些页面的窗口是半透明的（AndroidManifest 里的 @style/Page）；平时窗口底色就是页面底色，
 * 动的时候换成压暗的遮罩。下面那页由 Stack 记着。用法见 Nav；不是从卡片点开的页面用 Push.open 打开。
 */
final class Push implements Nav {
    private static final float PARALLAX = 0.3f;
    private static final int SCRIM = 0x33;

    private final Activity a;
    private final View page;
    private final int bg, minFling;
    private ValueAnimator motion;
    private float from;
    private boolean leaving, gone;

    Push(Activity a, View page, int bg) {
        this.a = a;
        this.page = page;
        this.bg = bg;
        minFling = ViewConfiguration.get(a).getScaledMinimumFlingVelocity() * 8;
        page.setElevation(a.getResources().getDisplayMetrics().density * 12); // 滑开时左边缘的阴影
    }

    /** 推进一个页面：关掉系统的切换动画，新页面自己从右边滑进来。 */
    static void open(Activity from, Intent i) {
        from.startActivity(i);
        from.overridePendingTransition(0, 0);
    }

    static void open(Activity from, Intent i, int request) {
        from.startActivityForResult(i, request);
        from.overridePendingTransition(0, 0);
    }

    @Override
    public Swipe wrap(boolean fresh) {
        Swipe s = new Swipe(a, new Swipe.Pages() {
            @Override
            public boolean has(int dir) {
                return dir < 0 && can();
            }

            @Override
            public void grab(int dir) {
                Push.this.grab();
            }

            @Override
            public void drag(float dx) {
                Push.this.drag(dx);
            }

            @Override
            public void release(float vx) {
                Push.this.release(vx);
            }
        });
        s.addView(page, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        if (fresh) enter();
        return s;
    }

    /** 从右边推进来。等第一帧要画的时候再开始，免得开头几帧被建页面的工作吃掉。 */
    @Override
    public void enter() {
        if (Build.VERSION.SDK_INT >= 34) {
            a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0);
            a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
        } else {
            a.overridePendingTransition(0, 0); // 从通知直接打开时也不要系统动画
        }
        place(width());
        page.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                page.getViewTreeObserver().removeOnPreDrawListener(this);
                if (!leaving) settle(0, 0, Push.this::rest); // 还没画出来就要关的，不再推进来
                return true;
            }
        });
    }

    @Override
    public boolean can() {
        return !leaving;
    }

    // 拖着的时候页面被别处关掉（返回键之类）就不再跟手，让滑出走完

    @Override
    public void grab() {
        if (leaving) return;
        if (motion != null) motion.cancel();
        from = page.getTranslationX();
    }

    @Override
    public void drag(float dx) {
        if (!leaving) place(Math.max(0, from + dx));
    }

    /** 过半或者往右甩一下就返回，否则弹回。 */
    @Override
    public void release(float vx) {
        if (leaving) return;
        boolean go = vx > minFling || page.getTranslationX() > width() / 2 && vx > -minFling;
        if (go) out(vx, a::onBackPressed);
        else settle(0, vx, this::rest);
    }

    @Override
    public void finish(Runnable done) {
        if (gone) {
            done.run();
            a.overridePendingTransition(0, 0);
            return;
        }
        if (!leaving) out(0, () -> finish(done));
    }

    @Override
    public void replaced() {
        gone = true;
    }

    private void out(float velocity, Runnable then) {
        leaving = true;
        settle(width(), velocity, () -> {
            gone = true;
            then.run();
        });
    }

    private void settle(float to, float velocity, Runnable end) {
        if (motion != null) motion.cancel();
        motion = Spring.PAGE.fling(page.getTranslationX(), to, velocity, this::place, end);
    }

    /** 停稳在原位：窗口底色换回页面底色。 */
    private void rest() {
        a.getWindow().getDecorView().setBackgroundColor(bg);
    }

    /** 页面放在 x：下面那页按比例从左边三成处回来，遮罩按比例变淡。 */
    private void place(float x) {
        x = Math.max(0, x); // 带着往左的速度弹回时不越过左边
        page.setTranslationX(x);
        float w = width(), q = 1 - Math.min(1f, x / w); // 1 是完全盖住
        a.getWindow().getDecorView().setBackgroundColor(Color.argb(Math.round(SCRIM * q), 0, 0, 0));
        Activity under = Stack.below(a);
        if (under != null) under.findViewById(android.R.id.content).setTranslationX(-PARALLAX * w * q);
    }

    private float width() {
        int w = page.getWidth();
        return w > 0 ? w : a.getResources().getDisplayMetrics().widthPixels;
    }

    /** 按创建顺序记下本进程的页面，好找到“下面那页”。在 App 里注册。 */
    static final class Stack implements Application.ActivityLifecycleCallbacks {
        private static final List<WeakReference<Activity>> all = new ArrayList<>();

        /** a 下面那页：同一个任务里比 a 先建、还没在关的最近一页。 */
        static Activity below(Activity a) {
            Activity found = null;
            for (WeakReference<Activity> r : all) {
                Activity x = r.get();
                if (x == a) return found;
                if (x != null && !x.isFinishing() && x.getTaskId() == a.getTaskId()) found = x;
            }
            return null;
        }

        @Override
        public void onActivityCreated(Activity a, Bundle state) {
            all.add(new WeakReference<>(a));
        }

        @Override
        public void onActivityDestroyed(Activity a) {
            for (Iterator<WeakReference<Activity>> it = all.iterator(); it.hasNext(); ) {
                Activity x = it.next().get();
                if (x == null || x == a) it.remove();
            }
        }

        /** 保险：回到前台的页面一定在原位、藏起来的卡片放回来，万一上面那页没动完就没了，也不会一直歪着、缺一块。 */
        @Override
        public void onActivityResumed(Activity a) {
            View content = a.findViewById(android.R.id.content);
            if (content != null) content.setTranslationX(0);
            Card.restore();
        }

        @Override
        public void onActivityStarted(Activity a) {
        }

        @Override
        public void onActivityPaused(Activity a) {
        }

        @Override
        public void onActivityStopped(Activity a) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity a, Bundle state) {
        }
    }
}
