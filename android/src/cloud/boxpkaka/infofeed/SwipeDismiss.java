package cloud.boxpkaka.infofeed;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

/**
 * 滑动移除：横向拖动时内容跟手，圆角随距离收到一半、略微缩小，背后露出“不需要”“删除”；
 * 超过 35% 宽度（或快速甩动）松手即接着手指的速度飞出，跨过阈值时震一下。没开始横向拖动前不消费事件，点按和纵向滚动照常。
 * 只认一个方向：首页右滑（左滑留给翻到下一页，见 Swipe）；能右滑返回的二级页面左滑，像 iOS 左滑删除。
 */
final class SwipeDismiss implements View.OnTouchListener {
    interface Callback {
        void dismissed(View front);
    }

    private final View behind;
    private final Callback callback;
    private final int slop, minFling, sign; // sign 为 1 是右滑，-1 是左滑
    private float downX, downY;
    private boolean swiping, armed;
    private VelocityTracker velocity;
    private GradientDrawable shape; // 前景的圆角背景，拖动时改圆角
    private float[] radii;

    SwipeDismiss(View front, View behind, Callback callback) {
        this(front, behind, callback, 1);
    }

    SwipeDismiss(View front, View behind, Callback callback, int sign) {
        this.behind = behind;
        this.callback = callback;
        this.sign = sign;
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
                Swipe.addRaw(velocity, e); // 这一行自己在跟手移动，按屏幕坐标记速度
                return false; // 让控件自己处理按下效果和点击
            case MotionEvent.ACTION_MOVE: {
                if (velocity != null) Swipe.addRaw(velocity, e);
                float dx = sign * (e.getRawX() - downX), dy = e.getRawY() - downY; // dx 朝移除方向为正
                if (!swiping && dx > slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    swiping = true;
                    v.setPressed(false);
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    behind.setVisibility(View.VISIBLE);
                    grabShape(v);
                }
                if (!swiping) return false;
                float x = Math.max(0, dx);
                follow(v, sign * x);
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
                    Swipe.addRaw(velocity, e);
                    velocity.computeCurrentVelocity(1000);
                    vx = sign * velocity.getXVelocity(); // 朝移除方向为正
                }
                recycle();
                boolean dismiss = e.getActionMasked() == MotionEvent.ACTION_UP && (armed || vx > minFling);
                // 用 ViewPropertyAnimator：列表复用这一行时 reset() 能把它取消掉。
                // 飞出用减速曲线 1-(1-t)^3，起步速度是 3 倍距离/时长，时长按松手速度算，就接上了手指的速度。
                float out = v.getWidth() * 1.15f - sign * v.getTranslationX();
                long ms = vx > 0 ? Math.round(3000 * out / vx) : 320;
                v.animate().translationX(dismiss ? sign * v.getWidth() * 1.15f : 0)
                        .setDuration(dismiss ? Math.max(140, Math.min(320, ms)) : Spring.STD.duration)
                        .setInterpolator(dismiss ? new DecelerateInterpolator(1.5f) : Spring.STD)
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
        float p = Math.min(1f, Math.abs(x) / Math.max(1f, v.getWidth() * 0.35f));
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

    /** 移走后这一行平滑收起：高度收到 0、淡出，下面的跟着往上挪；收完再 then。 */
    static void collapse(View row, Runnable then) {
        int from = row.getHeight();
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        ValueAnimator a = ValueAnimator.ofFloat(1, 0);
        a.setDuration(260);
        a.setInterpolator(new DecelerateInterpolator(1.5f));
        a.addUpdateListener(x -> {
            float f = (float) x.getAnimatedValue();
            lp.height = Math.max(1, Math.round(from * f));
            row.setAlpha(f);
            row.requestLayout();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator x) {
                then.run();
            }
        });
        a.start();
    }

    /** 从无到有展开（撤销移除时）：按 width 宽量出该有多高，从 0 长到那么高、淡入。 */
    static void expand(View row, int width) {
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int to = row.getMeasuredHeight();
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp == null || to <= 0) return;
        lp.height = 1;
        row.setAlpha(0f);
        ValueAnimator a = ValueAnimator.ofFloat(0, 1);
        a.setDuration(300);
        a.setInterpolator(new DecelerateInterpolator(1.5f));
        a.addUpdateListener(x -> {
            float f = (float) x.getAnimatedValue();
            lp.height = Math.max(1, Math.round(to * f));
            row.setAlpha(f);
            row.requestLayout();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator x) {
                unfold(row);
            }
        });
        a.start();
    }

    /** 列表复用收起过、展开到一半的行之前恢复成自然高度。 */
    static void unfold(View row) {
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp != null && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            row.requestLayout();
        }
        row.setAlpha(1f);
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
