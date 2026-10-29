package cloud.boxpkaka.infofeed;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 横向滑动翻页：左滑去下一页，右滑回上一页（没有上一页时是返回）。手指横向移动超过 touch slop、且明显比纵向多时接管，
 * 之后内容跟手，超过三成宽度或快速甩动松手就翻过去，否则弹回；跨过阈值时震一下。
 * 手指下面的控件自己还能往这个方向滚（标签栏、嵌在里面的另一个 Swipe）时让给它，到头了再由外层接管，所以可以一层套一层。
 * 跟手的样子和翻过去之后做什么由 Pages 决定，常用的两种：follow / turn 原地换内容，Back 返回上一个页面。
 */
final class Swipe extends FrameLayout {
    interface Pages {
        /** dir 为 1 是左滑（下一页），-1 是右滑（上一页或返回）。 */
        boolean has(int dir);

        /** 跟手：dx 是横向位移，右为正，只会落在 dir 那一侧。 */
        void drag(int dir, float dx);

        /** 松手：go 为 false 时回到原位。 */
        void release(int dir, boolean go);
    }

    private final Pages pages;
    private final int slop, minFling;
    private float downX, downY, startX;
    private int dir; // 0 是还没接管
    private boolean armed;
    private VelocityTracker velocity;

    Swipe(Context c, Pages pages) {
        super(c);
        this.pages = pages;
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity() * 8;
    }

    /** 嵌在另一个 Swipe 里时，外层靠它知道这一层还能不能翻。 */
    @Override
    public boolean canScrollHorizontally(int direction) {
        return pages.has(direction > 0 ? 1 : -1);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        track(e);
        return dir != 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        track(e);
        return true; // 没有子控件接手的空白处也能滑
    }

    private void track(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = e.getRawX();
            downY = e.getRawY();
            dir = 0;
            armed = false;
            if (velocity == null) velocity = VelocityTracker.obtain();
            else velocity.clear();
        }
        if (velocity != null) velocity.addMovement(e);
        if (action == MotionEvent.ACTION_MOVE) {
            if (dir == 0) {
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                int d = dx < 0 ? 1 : -1;
                if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f && pages.has(d)
                        && !scrolls(this, d, e.getX(), e.getY())) {
                    dir = d;
                    startX = e.getRawX();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
            if (dir != 0) {
                float dx = e.getRawX() - startX;
                dx = dir > 0 ? Math.min(0, dx) : Math.max(0, dx);
                pages.drag(dir, dx);
                boolean over = Math.abs(dx) > getWidth() * 0.3f;
                if (over != armed) {
                    armed = over;
                    if (over) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (dir != 0) {
                float vx = 0;
                if (velocity != null) {
                    velocity.computeCurrentVelocity(1000);
                    vx = -dir * velocity.getXVelocity(); // 朝翻页方向为正
                }
                pages.release(dir, action == MotionEvent.ACTION_UP && (armed ? vx > -minFling : vx > minFling));
                dir = 0;
            }
            if (velocity != null) {
                velocity.recycle();
                velocity = null;
            }
        }
    }

    /**
     * 手指下面有没有控件还能往这个方向滚：dir 为 1 时看能不能往右滚（露出右边的内容）。只看容器（单行文字溢出时也算
     * “能横滚”，不能让它挡住翻页）；输入框里横滑是移动光标、选字，一律让给它。
     */
    private static boolean scrolls(ViewGroup g, int dir, float x, float y) {
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != VISIBLE) continue;
            float cx = x + g.getScrollX() - c.getLeft() - c.getTranslationX();
            float cy = y + g.getScrollY() - c.getTop() - c.getTranslationY();
            if (cx < 0 || cy < 0 || cx >= c.getWidth() || cy >= c.getHeight()) continue;
            if (c instanceof EditText) return true;
            if (c instanceof ViewGroup && (c.canScrollHorizontally(dir) || scrolls((ViewGroup) c, dir, cx, cy))) {
                return true;
            }
        }
        return false;
    }

    // ---------- 原地换内容 ----------

    /** 内容跟手移动，离开越远越淡。 */
    static void follow(View v, float dx) {
        v.animate().cancel(); // 上一次翻页的动画还没走完时，以手指为准
        v.setTranslationX(dx);
        v.setAlpha(1 - 0.7f * Math.min(1f, Math.abs(dx) / Math.max(1, v.getWidth())));
    }

    /** 松手：翻过去时内容滑出，swap 换成新内容后从另一边滑进来；不翻就弹回。 */
    static void turn(View v, int dir, boolean go, Runnable swap) {
        v.animate().cancel();
        if (!go) {
            v.animate().translationX(0).alpha(1).setDuration(Spring.STD.duration).setInterpolator(Spring.STD).start();
            return;
        }
        float w = v.getWidth();
        v.animate().translationX(-dir * w * 0.6f).alpha(0).setDuration(120).setInterpolator(null)
                .withEndAction(() -> {
                    swap.run();
                    v.setTranslationX(dir * w * 0.3f);
                    v.animate().translationX(0).alpha(1).setDuration(Spring.STD.duration).setInterpolator(Spring.STD)
                            .start();
                })
                .start();
    }

    // ---------- 右滑返回 ----------

    /**
     * 右滑返回：整页跟手右移，左边露出下面的页面，由暗变亮；松手翻过去就接着滑出，再走 onBackPressed。
     * 能看到下面的页面是因为这些页面的窗口是半透明的（AndroidManifest 里的 @style/Page）；
     * 平时窗口底色就是页面底色，只有拖动时换成压暗的遮罩。
     */
    static final class Back {
        private final Activity a;
        private final View page;
        private final int bg;

        Back(Activity a, View page, int bg) {
            this.a = a;
            this.page = page;
            this.bg = bg;
        }

        void drag(float dx) {
            page.animate().cancel();
            page.setTranslationX(dx);
            a.getWindow().getDecorView().setBackgroundColor(scrim(dx));
        }

        void release(boolean go) {
            View decor = a.getWindow().getDecorView();
            page.animate().cancel();
            page.animate().translationX(go ? page.getWidth() : 0)
                    .setDuration(go ? 180 : Spring.STD.duration).setInterpolator(go ? null : Spring.STD)
                    .setUpdateListener(an -> decor.setBackgroundColor(scrim(page.getTranslationX())))
                    .withEndAction(() -> {
                        page.animate().setUpdateListener(null);
                        if (!go) {
                            decor.setBackgroundColor(bg);
                            return;
                        }
                        a.onBackPressed();
                        a.overridePendingTransition(0, 0); // 已经滑出去了，不再走 finish() 里的下滑动画
                    })
                    .start();
        }

        private int scrim(float dx) {
            float p = Math.min(1f, Math.max(0f, dx / Math.max(1, page.getWidth())));
            return Color.argb(Math.round(0x55 * (1 - p)), 0, 0, 0);
        }
    }

    /** 只能右滑返回的页面：把整页包起来。 */
    static Swipe back(Activity a, View page, int bg) {
        Back back = new Back(a, page, bg);
        Swipe s = new Swipe(a, new Pages() {
            @Override
            public boolean has(int dir) {
                return dir < 0;
            }

            @Override
            public void drag(int dir, float dx) {
                back.drag(dx);
            }

            @Override
            public void release(int dir, boolean go) {
                back.release(go);
            }
        });
        s.addView(page, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        return s;
    }

    // ---------- 跟着翻页滑的胶囊标签 ----------

    /**
     * 一排胶囊标签，选中的胶囊画在标签后面，可以停在两个标签之间：翻页时跟着手指从这个标签滑向下一个，文字颜色跟着交接。
     * 放在横向滚动的标签栏里时，胶囊跑到外面就把它带进来。只管前 count 个子控件，后面的（如“只看相关”）不动。
     */
    static final class Tabs extends LinearLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF pill = new RectF();
        private final ArgbEvaluator argb = new ArgbEvaluator();
        private final int on, off;
        private int count, target;
        private float pos;
        private ValueAnimator anim;

        Tabs(Context c, int fill, int on, int off) {
            super(c);
            paint.setColor(fill);
            this.on = on;
            this.off = off;
            setWillNotDraw(false);
        }

        void setCount(int count) {
            this.count = count;
        }

        /** 马上停在 pos：1.5 是第二、三个标签正中间。 */
        void at(float pos) {
            if (anim != null) anim.cancel();
            move(pos);
        }

        /** 用和翻页一样的弹簧滑到第 to 个；已经在往那里滑就不打断。 */
        void slide(int to) {
            if (anim != null && anim.isRunning() && target == to) return;
            if (anim != null) anim.cancel();
            target = to;
            if (pos == to) {
                move(to); // 标签刚重建时也要上色
                return;
            }
            anim = ValueAnimator.ofFloat(pos, to);
            anim.setDuration(Spring.STD.duration);
            anim.setInterpolator(Spring.STD);
            anim.addUpdateListener(a -> move((float) a.getAnimatedValue()));
            anim.start();
        }

        private void move(float p) {
            pos = p;
            for (int i = 0; i < count && i < getChildCount(); i++) {
                float near = Math.max(0f, 1 - Math.abs(p - i));
                ((TextView) getChildAt(i)).setTextColor((int) argb.evaluate(near, off, on));
            }
            reveal();
            invalidate();
        }

        /** 胶囊现在的位置：前后两个标签的边界按 pos 的小数部分插值。 */
        private boolean place() {
            if (count == 0 || getChildCount() < count) return false;
            float p = Math.max(0, Math.min(count - 1, pos));
            int i = (int) p;
            float f = p - i;
            View a = getChildAt(i), b = getChildAt(Math.min(i + 1, count - 1));
            pill.set(a.getLeft() + (b.getLeft() - a.getLeft()) * f, a.getTop(),
                    a.getRight() + (b.getRight() - a.getRight()) * f, a.getBottom());
            return true;
        }

        private void reveal() {
            if (!(getParent() instanceof HorizontalScrollView) || isLayoutRequested() || !place()) return;
            HorizontalScrollView s = (HorizontalScrollView) getParent();
            int x = s.getScrollX(), w = s.getWidth(), m = getPaddingLeft();
            if (pill.left - m < x) s.scrollTo(Math.round(pill.left - m), 0);
            else if (pill.right + m > x + w) s.scrollTo(Math.round(pill.right + m - w), 0);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (!place()) return;
            float r = pill.height() / 2;
            c.drawRoundRect(pill, r, r, paint);
        }
    }

    // ---------- 底部面板下拉关闭 ----------

    /**
     * 底部面板的外框：往下拉跟手，拉过四分之一高度或快速下甩就收起（等于点面板外面），否则弹回。
     * 面板里的列表还能往上滚时让给列表。
     */
    static final class Sheet extends LinearLayout {
        private final Dialog dialog;
        private final int slop, minFling;
        private float downX, downY, startY;
        private boolean dragging;
        private VelocityTracker velocity;

        Sheet(Context c, Dialog dialog) {
            super(c);
            this.dialog = dialog;
            setOrientation(VERTICAL);
            ViewConfiguration vc = ViewConfiguration.get(c);
            slop = vc.getScaledTouchSlop();
            minFling = vc.getScaledMinimumFlingVelocity() * 8;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            track(e);
            return dragging;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            track(e);
            return true;
        }

        private void track(MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downX = e.getRawX();
                downY = e.getRawY();
                dragging = false;
                if (velocity == null) velocity = VelocityTracker.obtain();
                else velocity.clear();
            }
            if (velocity != null) velocity.addMovement(e);
            if (action == MotionEvent.ACTION_MOVE) {
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                if (!dragging && dy > slop && dy > Math.abs(dx) * 1.5f && !scrollsUp(this, e.getX(), e.getY())) {
                    dragging = true;
                    startY = e.getRawY();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) setTranslationY(Math.max(0, e.getRawY() - startY));
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (dragging) {
                    dragging = false;
                    float vy = 0;
                    if (velocity != null) {
                        velocity.computeCurrentVelocity(1000);
                        vy = velocity.getYVelocity();
                    }
                    boolean close = action == MotionEvent.ACTION_UP
                            && (getTranslationY() > getHeight() * 0.25f ? vy > -minFling : vy > minFling);
                    animate().translationY(close ? getHeight() : 0)
                            .setDuration(close ? 160 : Spring.STD.duration).setInterpolator(close ? null : Spring.STD)
                            .withEndAction(close ? dialog::dismiss : null)
                            .start();
                }
                if (velocity != null) {
                    velocity.recycle();
                    velocity = null;
                }
            }
        }

        private static boolean scrollsUp(ViewGroup g, float x, float y) {
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View c = g.getChildAt(i);
                if (c.getVisibility() != VISIBLE) continue;
                float cx = x + g.getScrollX() - c.getLeft() - c.getTranslationX();
                float cy = y + g.getScrollY() - c.getTop() - c.getTranslationY();
                if (cx < 0 || cy < 0 || cx >= c.getWidth() || cy >= c.getHeight()) continue;
                if (c.canScrollVertically(-1)) return true;
                if (c instanceof ViewGroup && scrollsUp((ViewGroup) c, cx, cy)) return true;
            }
            return false;
        }
    }
}
