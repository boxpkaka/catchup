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
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.function.IntSupplier;

/**
 * 横向滑动：左滑去下一页，右滑回上一页（没有上一页时是返回）。手指横向移动超过 touch slop、且明显比纵向多时接管，
 * 之后完全跟手，松手时把手指的速度交给 Pages 收尾。
 * 手指下面的控件自己还能往这个方向滚（标签栏、嵌在里面的另一个 Swipe、能右滑移除的行）时让给它，到头了再由外层接管，
 * 所以可以一层套一层。常用的 Pages 是 Pager（横向排开的几页）和 Push（右滑返回）。
 */
final class Swipe extends FrameLayout {
    interface Pages {
        /** 能不能接管这个方向的横滑：dir 为 1 是左滑，-1 是右滑。 */
        boolean has(int dir);

        /** 开始拖动，dir 是起手的方向；正在弹的话就地停住，从那里接着拖。 */
        void grab(int dir);

        /** 跟手：dx 是开始拖动以来的横向位移，右为正。 */
        void drag(float dx);

        /** 松手：vx 是横向速度（像素/秒，右为正），被打断时为 0。 */
        void release(float vx);
    }

    private final Pages pages;
    private final int slop;
    private float downX, downY, startX;
    private boolean dragging;
    private VelocityTracker velocity;

    Swipe(Context c, Pages pages) {
        super(c);
        this.pages = pages;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    /** 嵌在另一个 Swipe 里时，外层靠它知道这一层还能不能翻。 */
    @Override
    public boolean canScrollHorizontally(int direction) {
        return pages.has(direction > 0 ? 1 : -1);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        track(e);
        return dragging;
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
            dragging = false;
            if (velocity == null) velocity = VelocityTracker.obtain();
            else velocity.clear();
        }
        if (velocity != null) addRaw(velocity, e);
        if (action == MotionEvent.ACTION_MOVE) {
            if (!dragging) {
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                int d = dx < 0 ? 1 : -1;
                if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f && pages.has(d)
                        && !scrolls(this, d, e.getX(), e.getY())) {
                    dragging = true;
                    startX = e.getRawX();
                    pages.grab(d);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
            if (dragging) pages.drag(e.getRawX() - startX);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (dragging) {
                dragging = false;
                float vx = 0;
                if (velocity != null && action == MotionEvent.ACTION_UP) {
                    velocity.computeCurrentVelocity(1000);
                    vx = velocity.getXVelocity();
                }
                pages.release(vx);
            }
            if (velocity != null) {
                velocity.recycle();
                velocity = null;
            }
        }
    }

    /** 按屏幕坐标记速度：跟手移动的控件收到的是自己坐标系里的位置，直接记会把速度算小。 */
    static void addRaw(VelocityTracker v, MotionEvent e) {
        MotionEvent raw = MotionEvent.obtain(e);
        raw.setLocation(e.getRawX(), e.getRawY());
        v.addMovement(raw);
        raw.recycle();
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

    // ---------- 横向排开的几页 ----------

    /**
     * 横向排开的几页，像 iOS 的分页滚动：position 是小数页码，第 i 页放在 (i - position) 个宽度处，拖动时相邻的页
     * 跟着手指一起进来；松手带着手指的速度弹到一页（过半或者甩一下就到下一页），弹的途中按住可以接着拖。
     * 两头没有下一页时：第一页右滑可以接 back（返回）；rubber 为 true 时像橡皮筋一样越拉越紧、松手弹回；
     * 都不是就交给外层。frame 是接手势的外框，把要横滑的整块内容放进去。
     */
    static final class Pager implements Pages {
        interface Host {
            int count();

            /** 第 index 页，要露出来时才调用，可以这时再建。 */
            View page(int index);

            /** 每动一下：标签胶囊跟着走。 */
            void moved(float position);

            /** 停在了第 index 页。 */
            void settled(int index);
        }

        final Swipe frame;
        private final Host host;
        private final boolean rubber;
        private final int minFling;
        private final SparseArray<View> shown = new SparseArray<>();
        private Nav back;
        private ScrollView scroll;
        private IntSupplier top;
        private ValueAnimator motion;
        private float position, from;
        private int index, start, lift;
        private boolean backing;

        Pager(Context c, Host host, boolean rubber) {
            this.host = host;
            this.rubber = rubber;
            minFling = ViewConfiguration.get(c).getScaledMinimumFlingVelocity() * 8;
            frame = new Swipe(c, this);
        }

        /** 第一页再往右滑就是返回。 */
        void back(Nav p) {
            back = p;
        }

        /**
         * 这几页和别的内容一起放在竖着滚的 scroll 里：已经往下滚过 top 时，别的页往下错开同样多，看起来是从 top 开始的，
         * 翻过去以后真的滚回 top。
         */
        void scrollsWith(ScrollView s, IntSupplier top) {
            scroll = s;
            this.top = top;
        }

        int index() {
            return index;
        }

        /** 点标签：从当前位置滑到第 i 页。 */
        void go(int i) {
            if (!moving()) {
                if (i == index) return;
                begin();
            }
            settle(i, 0);
        }

        /** 直接停在第 i 页，不要动画。 */
        void jump(int i) {
            if (motion != null) motion.cancel();
            index = start = i;
            lift = 0;
            move(i);
            host.settled(i);
        }

        /** 页面重建了：丢掉旧的，停在第 i 页。 */
        void reset(int i) {
            for (int k = 0; k < shown.size(); k++) shown.valueAt(k).setVisibility(GONE);
            shown.clear();
            jump(i);
        }

        @Override
        public boolean has(int dir) {
            if (moving()) return true; // 弹的途中可以接住
            int to = index + dir;
            if (to >= 0 && to < host.count()) return true;
            if (dir < 0 && index == 0 && back != null) return back.can();
            return rubbery();
        }

        @Override
        public void grab(int dir) {
            boolean caught = moving();
            if (caught) motion.cancel();
            backing = !caught && dir < 0 && index == 0 && back != null;
            if (backing) {
                back.grab();
                return;
            }
            if (!caught) begin();
            int last = Math.max(0, host.count() - 1);
            // 在橡皮筋里接住：换回手指拉出去的距离，接着拉才不会跳
            from = position < 0 ? -unband(-position) : position > last ? last + unband(position - last) : position;
        }

        @Override
        public void drag(float dx) {
            if (backing) {
                back.drag(dx);
                return;
            }
            float p = from - dx / width();
            int last = Math.max(0, host.count() - 1);
            if (p < 0) p = rubbery() ? -band(-p) : 0;
            else if (p > last) p = rubbery() ? last + band(p - last) : last;
            move(p);
        }

        @Override
        public void release(float vx) {
            if (backing) {
                backing = false;
                back.release(vx);
                return;
            }
            int to = vx < -minFling ? (int) Math.floor(position) + 1
                    : vx > minFling ? (int) Math.ceil(position) - 1
                    : Math.round(position);
            settle(Math.max(0, Math.min(host.count() - 1, to)), -vx);
        }

        private boolean moving() {
            return motion != null && motion.isRunning();
        }

        private boolean rubbery() {
            return rubber && host.count() > 1;
        }

        private float width() {
            return Math.max(1, frame.getWidth());
        }

        /** 从停着的一页开始翻：记下起点和要错开多少。 */
        private void begin() {
            start = index;
            lift = scroll == null ? 0 : Math.max(0, scroll.getScrollY() - top.getAsInt());
        }

        /** iOS 的橡皮筋：x 是拉出去几页，越远越拉不动，最多接近一页。 */
        private static float band(float x) {
            return 1 - 1 / (x * 0.55f + 1);
        }

        private static float unband(float y) {
            return (1 / (1 - Math.min(y, 0.99f)) - 1) / 0.55f;
        }

        /** velocity 是 position 方向的速度（像素/秒）。 */
        private void settle(int to, float velocity) {
            if (motion != null) motion.cancel();
            float w = width(), last = Math.max(0, host.count() - 1);
            // 没有橡皮筋的（嵌在外层里的）弹到头不越界，免得露出空白
            motion = Spring.PAGE.fling(position * w, to * w, velocity,
                    x -> move(rubbery() ? x / w : Math.max(0, Math.min(last, x / w))), () -> land(to));
        }

        private void land(int to) {
            if (to != start && lift > 0) scroll.scrollTo(0, scroll.getScrollY() - lift);
            index = start = to;
            lift = 0;
            move(to);
            host.settled(to);
        }

        private void move(float p) {
            position = p;
            float w = width();
            int lo = (int) Math.floor(p), hi = (int) Math.ceil(p);
            for (int k = shown.size() - 1; k >= 0; k--) {
                int i = shown.keyAt(k);
                if (i < lo || i > hi) {
                    shown.valueAt(k).setVisibility(GONE);
                    shown.removeAt(k);
                }
            }
            for (int i = Math.max(0, lo); i <= Math.min(hi, host.count() - 1); i++) {
                View v = shown.get(i);
                if (v == null) {
                    v = host.page(i);
                    v.setVisibility(VISIBLE);
                    shown.put(i, v);
                }
                v.setTranslationX((i - p) * w);
                v.setTranslationY(i == start ? 0 : lift);
            }
            host.moved(p);
        }
    }

    // ---------- 跟着翻页走的胶囊标签 ----------

    /**
     * 一排标签，选中的胶囊画在标签后面，可以停在两个标签之间：翻页时由 Pager 每帧告诉它位置，胶囊跟着手指从这个标签
     * 滑向下一个，文字颜色跟着交接。放在横向滚动的标签栏里时，胶囊跑到外面就把它带进来。
     * 只管前 count 个子控件，后面的（如“只看相关”）不动。
     */
    static final class Tabs extends LinearLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF pill = new RectF();
        private final ArgbEvaluator argb = new ArgbEvaluator();
        private final int on, off;
        private int count;
        private float pos;

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

        /** 停在 pos：1.5 是第二、三个标签正中间。 */
        void at(float pos) {
            this.pos = pos;
            for (int i = 0; i < count && i < getChildCount(); i++) {
                float near = Math.max(0f, 1 - Math.abs(pos - i));
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

    // ---------- 底部面板 ----------

    /**
     * 底部面板，像 iOS 的 sheet：从底下升起、背后一起渐暗；往下拉跟手，背后跟着变亮，拉过四分之一高度或快速下甩就带着
     * 速度收下去，否则弹回；点面板外面、返回键、代码里的 dismiss() 也都先收下去再关。面板里的列表还能往上滚时让给列表。
     * 窗口铺满整屏（遮罩自己画，盖住状态栏和导航栏后面），面板底部按导航栏和键盘的高度留空。内容放进 box。
     */
    static final class Sheet extends Dialog {
        private static final int DIM = 0x6B;

        final LinearLayout box;
        private final FrameLayout root;
        private final Activity owner;
        private ValueAnimator motion;
        private boolean closing;
        private int pad = -1;

        Sheet(Activity a) {
            super(a);
            owner = a;
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            root = new FrameLayout(a);
            root.setOnClickListener(v -> cancel()); // 点面板外面
            box = new Box(a);
            box.setClickable(true); // 面板上的点按不漏到外面
            root.addView(box, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM));
            setContentView(root);
            Window w = getWindow();
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setWindowAnimations(0); // 进出场自己做
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            w.getDecorView().setSystemUiVisibility(a.getWindow().getDecorView().getSystemUiVisibility()); // 状态栏图标颜色不变
            if (Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(false);
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.setFitInsetsTypes(0); // 浮动窗口默认让开系统栏，遮罩就盖不到状态栏和导航栏后面
                w.setAttributes(lp);
                root.setOnApplyWindowInsetsListener((v, insets) -> {
                    if (pad < 0) pad = box.getPaddingBottom();
                    int bottom = insets.getInsets(WindowInsets.Type.navigationBars() | WindowInsets.Type.ime()).bottom;
                    box.setPadding(box.getPaddingLeft(), box.getPaddingTop(), box.getPaddingRight(), pad + bottom);
                    return WindowInsets.CONSUMED;
                });
            }
        }

        @Override
        public void show() {
            closing = false;
            box.setTranslationY(owner.getResources().getDisplayMetrics().heightPixels); // 第一帧在屏幕外
            dim(0);
            super.show();
            box.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    box.getViewTreeObserver().removeOnPreDrawListener(this);
                    slide(box.getHeight(), 0, 0, null);
                    return true;
                }
            });
        }

        @Override
        public void dismiss() {
            if (closing) return;
            if (!isShowing() || owner.isFinishing() || owner.isDestroyed() || box.getHeight() == 0) {
                super.dismiss();
                return;
            }
            close(0);
        }

        /** 带着速度收下去，收完再真的关。 */
        private void close(float velocity) {
            closing = true;
            slide(box.getTranslationY(), box.getHeight(), velocity, () -> {
                if (!owner.isDestroyed()) super.dismiss();
            });
        }

        private void slide(float from, float to, float velocity, Runnable end) {
            if (motion != null) motion.cancel();
            motion = Spring.PAGE.fling(from, to, velocity, y -> {
                box.setTranslationY(Math.max(0, y));
                dim(1 - Math.max(0, y) / Math.max(1, box.getHeight()));
            }, end);
        }

        private void dim(float p) {
            root.setBackgroundColor(Color.argb(Math.round(DIM * Math.max(0, Math.min(1, p))), 0, 0, 0));
        }

        /** 面板本身：接住往下拉的手势。 */
        private final class Box extends LinearLayout {
            private final int slop, minFling;
            private float downX, downY, startY;
            private boolean dragging;
            private VelocityTracker velocity;

            Box(Context c) {
                super(c);
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
                if (closing) return;
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    downX = e.getRawX();
                    downY = e.getRawY();
                    dragging = false;
                    if (velocity == null) velocity = VelocityTracker.obtain();
                    else velocity.clear();
                }
                if (velocity != null) addRaw(velocity, e);
                if (action == MotionEvent.ACTION_MOVE) {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!dragging && dy > slop && dy > Math.abs(dx) * 1.5f && !scrollsUp(this, e.getX(), e.getY())) {
                        dragging = true;
                        if (motion != null) motion.cancel();
                        startY = e.getRawY() - getTranslationY();
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) {
                        float y = Math.max(0, e.getRawY() - startY);
                        setTranslationY(y);
                        dim(1 - y / Math.max(1, getHeight()));
                    }
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (dragging) {
                        dragging = false;
                        float vy = 0;
                        if (velocity != null && action == MotionEvent.ACTION_UP) {
                            velocity.computeCurrentVelocity(1000);
                            vy = velocity.getYVelocity();
                        }
                        boolean close = action == MotionEvent.ACTION_UP
                                && (getTranslationY() > getHeight() * 0.25f ? vy > -minFling : vy > minFling);
                        if (close) close(vy);
                        else slide(getTranslationY(), 0, vy, null);
                    }
                    if (velocity != null) {
                        velocity.recycle();
                        velocity = null;
                    }
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
