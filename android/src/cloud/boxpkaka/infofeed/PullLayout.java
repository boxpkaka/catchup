package cloud.boxpkaka.infofeed;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.AbsListView;
import android.widget.FrameLayout;

/**
 * 下拉刷新：列表在顶部时向下拉，像 iOS 的橡皮筋一样越拉越紧，露出加载指示器，随拉动距离放大、转动；拉过阈值时震一下，
 * 松手后带着手指的速度弹到刷新位置（或弹回），指示器边变形边转，直到刷新结束；弹的途中按住可以接着拉。
 * 框架里没有 SwipeRefreshLayout，这里自己实现。
 */
final class PullLayout extends FrameLayout {
    interface OnRefresh {
        void refresh();
    }

    private final AbsListView list;
    private final FrameLayout indicator;
    private final Shapes.Loader loader;
    private final int slop, threshold, rest;
    private float downY, downX, offset, pulled;
    private boolean dragging, refreshing, armed;
    private OnRefresh listener;
    private VelocityTracker velocity;
    private ValueAnimator motion;

    PullLayout(Context c, Theme theme, AbsListView list) {
        super(c);
        this.list = list;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        threshold = theme.dp(96);
        rest = theme.dp(78);
        indicator = new FrameLayout(c);
        indicator.setBackground(theme.round(theme.pc, 99));
        indicator.setAlpha(0);
        loader = new Shapes.Loader(c, theme.pri);
        indicator.addView(loader, new LayoutParams(theme.dp(34), theme.dp(34), Gravity.CENTER));
        LayoutParams lp = new LayoutParams(theme.dp(56), theme.dp(56), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        lp.topMargin = theme.dp(12);
        addView(indicator, lp);
        addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void setOnRefresh(OnRefresh l) {
        listener = l;
    }

    /** 刷新完成（成功或失败）后调用，列表回弹。 */
    void done() {
        if (!refreshing) return;
        refreshing = false;
        loader.stop();
        animateTo(0, 0);
    }

    private boolean atTop() {
        if (list.getChildCount() == 0) return true;
        return list.getFirstVisiblePosition() == 0 && list.getChildAt(0).getTop() >= list.getPaddingTop();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (refreshing) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getY();
                downX = e.getX();
                dragging = false;
                if (velocity == null) velocity = VelocityTracker.obtain();
                else velocity.clear();
                velocity.addMovement(e);
                break;
            case MotionEvent.ACTION_MOVE:
                if (velocity != null) velocity.addMovement(e);
                float dy = e.getY() - downY, dx = Math.abs(e.getX() - downX);
                if (dy > slop && dy > dx && atTop()) {
                    dragging = true;
                    if (motion != null) motion.cancel();
                    pulled = unband(offset); // 正在弹回时从当前位置接着拉
                    downY = e.getY() - pulled;
                    armed = offset >= threshold;
                }
                break;
            default:
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) return super.onTouchEvent(e);
        if (velocity != null) velocity.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                pulled = Math.max(0, e.getY() - downY);
                setOffset(band(pulled));
                boolean over = offset >= threshold;
                if (over != armed) {
                    armed = over;
                    if (over) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                float vy = 0;
                if (velocity != null) {
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        velocity.computeCurrentVelocity(1000);
                        vy = velocity.getYVelocity() * slope(pulled); // 手指的速度换成被橡皮筋拉住后的速度
                    }
                    velocity.recycle();
                    velocity = null;
                }
                if (offset >= threshold && listener != null) {
                    refreshing = true;
                    animateTo(rest, vy);
                    loader.start();
                    listener.refresh();
                } else {
                    animateTo(0, vy);
                }
                return true;
            default:
                return true;
        }
    }

    private void setOffset(float v) {
        offset = v;
        list.setTranslationY(v);
        float progress = Math.min(1f, v / threshold);
        indicator.setAlpha(Math.min(1f, v / (threshold * 0.4f)));
        indicator.setScaleX(0.6f + 0.4f * progress);
        indicator.setScaleY(0.6f + 0.4f * progress);
        if (!refreshing) loader.setRotation(progress * 180);
    }

    private void animateTo(float target, float velocity) {
        if (motion != null) motion.cancel();
        motion = Spring.PAGE.fling(offset, target, velocity, v -> setOffset(Math.max(0, v)), null);
    }

    /** iOS 的橡皮筋：手指拉了 x，列表只下来这么多，越远越拉不动，最多接近整个高度。 */
    private float band(float x) {
        float d = Math.max(1, getHeight());
        return (1 - 1 / (x * 0.55f / d + 1)) * d;
    }

    /** band 的反函数：列表下来了 y，手指要拉多少。 */
    private float unband(float y) {
        float d = Math.max(1, getHeight());
        return d / 0.55f * (1 / (1 - Math.min(y, d * 0.99f) / d) - 1);
    }

    /** band 在 x 处的斜率：手指每移动 1，列表移动多少。 */
    private float slope(float x) {
        float d = Math.max(1, getHeight()), k = x * 0.55f / d + 1;
        return 0.55f / (k * k);
    }
}
