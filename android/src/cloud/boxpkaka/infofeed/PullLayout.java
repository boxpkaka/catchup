package cloud.boxpkaka.infofeed;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.AbsListView;
import android.widget.FrameLayout;

/**
 * 下拉刷新：列表在顶部时向下拉，露出图标里的 C 形圆环，圆环随拉动距离画出；
 * 拉过阈值时朱砂点“被接住”并震一下，松手后圆环转动直到刷新结束。框架里没有 SwipeRefreshLayout，这里自己实现。
 */
final class PullLayout extends FrameLayout {
    interface OnRefresh {
        void refresh();
    }

    private final Theme theme;
    private final AbsListView list;
    private final Indicator indicator;
    private final int slop, threshold, rest;
    private float downY, downX, offset;
    private boolean dragging, refreshing, armed;
    private OnRefresh listener;
    private ValueAnimator spin;

    PullLayout(Context c, Theme theme, AbsListView list) {
        super(c);
        this.theme = theme;
        this.list = list;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        threshold = theme.dp(84);
        rest = theme.dp(64);
        indicator = new Indicator(c);
        LayoutParams lp = new LayoutParams(theme.dp(32), theme.dp(32), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        lp.topMargin = theme.dp(18);
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
        if (spin != null) spin.cancel();
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
                    startSpin();
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
        indicator.progress = Math.min(1f, v / threshold);
        indicator.setAlpha(Math.min(1f, v / (threshold * 0.4f)));
        indicator.invalidate();
    }

    private void animateTo(float target) {
        ValueAnimator a = ValueAnimator.ofFloat(offset, target);
        a.setDuration(260);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(v -> setOffset((float) v.getAnimatedValue()));
        a.start();
    }

    private void startSpin() {
        spin = ValueAnimator.ofFloat(0, 360);
        spin.setDuration(900);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(v -> indicator.setRotation((float) v.getAnimatedValue()));
        spin.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                indicator.setRotation(0);
            }
        });
        spin.start();
    }

    /** 图标里的 C：开口朝右的圆环，进度到 1 时开口处出现朱砂点。 */
    private final class Indicator extends View {
        float progress;
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        Indicator(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeCap(Paint.Cap.ROUND);
            ring.setColor(theme.ink);
            dot.setColor(Theme.CINNABAR);
            setAlpha(0);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), stroke = w * 0.16f, r = w * 0.32f;
            ring.setStrokeWidth(stroke);
            box.set(w / 2 - r, w / 2 - r, w / 2 + r, w / 2 + r);
            // 和图标一致：从右上 40° 开始逆时针画到右下，最多 280°
            canvas.drawArc(box, -40, -280 * progress, false, ring);
            if (progress >= 1f) canvas.drawCircle(w / 2 + r + stroke * 0.2f, w / 2, stroke * 0.62f, dot);
        }
    }
}
