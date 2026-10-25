package cloud.boxpkaka.infofeed;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 左滑移除：横向拖动时内容跟手，背后露出“不需要”；超过 35% 宽度（或快速甩动）松手即移出，
 * 跨过阈值时震一下。没开始横向拖动前不消费事件，点按和纵向滚动照常。
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
                if (!swiping && dx < -slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    swiping = true;
                    v.setPressed(false);
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    behind.setVisibility(View.VISIBLE);
                }
                if (!swiping) return false;
                float x = Math.min(0, dx);
                v.setTranslationX(x);
                boolean over = -x > v.getWidth() * 0.35f;
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
                boolean dismiss = e.getActionMasked() == MotionEvent.ACTION_UP && (armed || vx < -minFling);
                if (dismiss) {
                    v.animate().translationX(-v.getWidth()).alpha(0f).setDuration(180)
                            .withEndAction(() -> callback.dismissed(v)).start();
                } else {
                    v.animate().translationX(0).setDuration(200).withEndAction(() -> behind.setVisibility(View.INVISIBLE))
                            .start();
                }
                return true;
            }
            default:
                return false;
        }
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
        front.setTranslationX(0);
        front.setAlpha(1f);
        behind.setVisibility(View.INVISIBLE);
        behind.setAlpha(0.45f);
    }
}
