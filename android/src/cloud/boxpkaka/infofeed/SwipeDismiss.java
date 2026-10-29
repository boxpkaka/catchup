package cloud.boxpkaka.infofeed;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 右滑移除：横向拖动时内容跟手，圆角随距离收到一半、略微缩小，背后露出“不需要”；
 * 超过 35% 宽度（或快速甩动）松手即用弹簧飞出，跨过阈值时震一下。没开始横向拖动前不消费事件，点按和纵向滚动照常。
 * 只认右滑：左滑留给首页翻到下一页（见 Swipe）。
 */
final class SwipeDismiss implements View.OnTouchListener {
    interface Callback {
        void dismissed(View front);
    }

    private final View behind;
    private final Callback callback;
    private final int slop, minFling;
    private float downX, downY;
    private boolean swiping, armed;
    private VelocityTracker velocity;
    private GradientDrawable shape; // 前景的圆角背景，拖动时改圆角
    private float[] radii;

    SwipeDismiss(View front, View behind, Callback callback) {
        this.behind = behind;
        this.callback = callback;
        ViewConfiguration vc = ViewConfiguration.get(front.getContext());
        slop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity() * 8;
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                swiping = armed = false;
                velocity = VelocityTracker.obtain();
                velocity.addMovement(e);
                return false; // 让控件自己处理按下效果和点击
            case MotionEvent.ACTION_MOVE: {
                if (velocity != null) velocity.addMovement(e);
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                if (!swiping && dx > slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    swiping = true;
                    v.setPressed(false);
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    behind.setVisibility(View.VISIBLE);
                    grabShape(v);
                }
                if (!swiping) return false;
                float x = Math.max(0, dx);
                follow(v, x);
                boolean over = x > v.getWidth() * 0.35f;
                if (over != armed) {
                    armed = over;
                    if (over) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    behind.animate().alpha(over ? 1f : 0.45f).setDuration(120).start();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!swiping) {
                    recycle();
                    return false;
                }
                swiping = false;
                float vx = 0;
                if (velocity != null) {
                    velocity.addMovement(e);
                    velocity.computeCurrentVelocity(1000);
                    vx = velocity.getXVelocity();
                }
                recycle();
                boolean dismiss = e.getActionMasked() == MotionEvent.ACTION_UP && (armed || vx > minFling);
                // 用 ViewPropertyAnimator：列表复用这一行时 reset() 能把它取消掉
                v.animate().translationX(dismiss ? v.getWidth() * 1.15f : 0)
                        .setDuration(Spring.STD.duration).setInterpolator(Spring.STD)
                        .setUpdateListener(an -> morph(v, v.getTranslationX()))
                        .withEndAction(() -> {
                            v.animate().setUpdateListener(null);
                            if (dismiss) callback.dismissed(v);
                            else behind.setVisibility(View.INVISIBLE);
                        })
                        .start();
                return true;
            }
            default:
                return false;
        }
    }

    private void grabShape(View v) {
        Drawable bg = v.getBackground();
        if (bg instanceof RippleDrawable) bg = ((RippleDrawable) bg).getDrawable(0);
        shape = bg instanceof GradientDrawable ? (GradientDrawable) bg : null;
        radii = shape == null ? null : shape.getCornerRadii();
    }

    private void follow(View v, float x) {
        v.setTranslationX(x);
        morph(v, x);
    }

    /** 跟着位移改样子：离开得越远，圆角越小（最多收到一半），整体越小（最多 3%）。 */
    private void morph(View v, float x) {
        float p = Math.min(1f, x / Math.max(1f, v.getWidth() * 0.35f));
        v.setScaleX(1 - 0.03f * p);
        v.setScaleY(1 - 0.03f * p);
        if (radii == null) return;
        float[] r = new float[radii.length];
        for (int i = 0; i < r.length; i++) r[i] = radii[i] * (1 - 0.5f * p);
        shape.setCornerRadii(r);
    }

    private void recycle() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    /** 列表复用这一行之前恢复原位。 */
    static void reset(View front, View behind) {
        front.animate().cancel();
        front.animate().setUpdateListener(null);
        front.setTranslationX(0);
        front.setScaleX(1f);
        front.setScaleY(1f);
        front.setAlpha(1f);
        behind.setVisibility(View.INVISIBLE);
        behind.setAlpha(0.45f);
    }
}
