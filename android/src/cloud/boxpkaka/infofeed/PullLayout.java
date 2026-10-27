package cloud.boxpkaka.infofeed;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.AbsListView;
import android.widget.FrameLayout;

/**
 * 下拉刷新：列表在顶部时向下拉，露出加载指示器，随拉动距离放大、转动；拉过阈值时震一下，
 * 松手后指示器边变形边转，直到刷新结束。框架里没有 SwipeRefreshLayout，这里自己实现。
 */
final class PullLayout extends FrameLayout {
    interface OnRefresh {
        void refresh();
    }

    private final AbsListView list;
    private final FrameLayout indicator;
    private final Shapes.Loader loader;
    private final int slop, threshold, rest;
    private float downY, downX, offset;
    private boolean dragging, refreshing, armed;
    private OnRefresh listener;

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
        animateTo(0);
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
                break;
            case MotionEvent.ACTION_MOVE:
                float dy = e.getY() - downY, dx = Math.abs(e.getX() - downX);
                if (dy > slop && dy > dx && atTop()) {
                    dragging = true;
                    downY = e.getY();
                    armed = false;
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
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                float pulled = Math.max(0, e.getY() - downY);
                setOffset(pulled * 0.5f); // 阻尼：手指移动一半的距离
                boolean over = offset >= threshold;
                if (over != armed) {
                    armed = over;
                    if (over) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (offset >= threshold && listener != null) {
                    refreshing = true;
                    animateTo(rest);
                    loader.start();
                    listener.refresh();
                } else {
                    animateTo(0);
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

    private void animateTo(float target) {
        ValueAnimator a = ValueAnimator.ofFloat(offset, target);
        a.setDuration(260);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(v -> setOffset((float) v.getAnimatedValue()));
        a.start();
    }
}
