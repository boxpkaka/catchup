package cloud.boxpkaka.infofeed;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

/**
 * 竖着滚的页面：到顶以后接着往下拉（或到底以后接着往上拉），内容整块跟着手指走，字不变形，越拉越紧
 * （和首页下拉刷新同一条橡皮筋，见 PullLayout），松手带着手指的速度按 PAGE 弹簧弹回，弹的途中按住可以接着拉。
 * 露出来的是页面底下的雾；有彩色头部的页面用 onPull 让头部原地变高，顶边一直贴着屏幕顶，把空出来的地方填满
 * （雾面金属是硬的，不像橡胶）。
 * 系统的拉伸回弹（Android 12 起把内容纵向拉长）关掉。
 */
final class Elastic extends ScrollView {
    /**
     * offset 是内容此刻的位移：正的是到顶往下拉，负的是到底往上拉，0 是回到原位。返回 true 表示页面自己用布局让出了
     * 这段距离（头部变高，下面的内容跟着往下），内容不用再整块平移。
     */
    interface OnPull {
        boolean pulled(float offset);
    }

    private final int slop;
    private OnPull listener;
    private VelocityTracker velocity;
    private ValueAnimator motion;
    private float downX, downY, lastY, raw, offset; // raw：手指拉了多远（带方向），offset = band(raw)
    private boolean pulling;

    Elastic(Context c) {
        super(c);
        setOverScrollMode(OVER_SCROLL_NEVER);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    void setOnPull(OnPull l) {
        listener = l;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        boolean scrolling = super.onInterceptTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = lastY = e.getY();
                if (velocity == null) velocity = VelocityTracker.obtain();
                else velocity.clear();
                velocity.addMovement(e);
                if (motion != null && motion.isRunning()) { // 弹的途中接住：从这里接着拉
                    motion.cancel();
                    if (offset != 0) start(unband(offset));
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (velocity != null) velocity.addMovement(e);
                float dy = e.getY() - downY;
                if (!pulling && Math.abs(dy) > slop && Math.abs(dy) > Math.abs(e.getX() - downX) && atEdge(dy)) {
                    lastY = e.getY();
                    start(0);
                }
                break;
            default:
                break;
        }
        return pulling || scrolling;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (velocity == null) velocity = VelocityTracker.obtain();
        if (action == MotionEvent.ACTION_DOWN) {
            velocity.clear();
            downX = e.getX();
            downY = lastY = e.getY();
        }
        velocity.addMovement(e);
        if (action == MotionEvent.ACTION_POINTER_UP && pulling) { // 换了一根手指：从剩下那根的位置接着算
            int keep = e.getActionIndex() == 0 ? 1 : 0;
            lastY = e.getY(keep);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float y = e.getY(), dy = y - lastY;
            lastY = y;
            if (!pulling && dy != 0 && Math.abs(y - downY) > slop && atEdge(dy)) {
                // 正在滚动的页面滚到了头，手指还在往外拉：后面这段交给橡皮筋，滚动先停下
                stopScrolling(e);
                start(0);
            }
            if (pulling) {
                float next = raw + dy;
                if (raw != 0 && Math.signum(next) != Math.signum(raw)) { // 推回原位还在往回推：接着滚动
                    pulling = false;
                    setOffset(0);
                    MotionEvent down = MotionEvent.obtain(e);
                    down.setAction(MotionEvent.ACTION_DOWN);
                    super.onTouchEvent(down);
                    down.recycle();
                    return true;
                }
                raw = next;
                setOffset(band(raw));
                return true;
            }
        }
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && pulling) {
            pulling = false;
            stopScrolling(e); // 拉的过程中滚动没收到手指的移动，不能让它按旧的速度甩出去
            float v = 0;
            if (action == MotionEvent.ACTION_UP) {
                velocity.computeCurrentVelocity(1000);
                v = velocity.getYVelocity() * slope(raw); // 手指的速度换成被橡皮筋拉住后的速度
            }
            settle(v);
            return true;
        }
        return super.onTouchEvent(e);
    }

    private void stopScrolling(MotionEvent e) {
        MotionEvent cancel = MotionEvent.obtain(e);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.onTouchEvent(cancel);
        cancel.recycle();
    }

    /** 往 dy 的方向已经滚到头了：往下拉时在顶上，往上推时在底下。 */
    private boolean atEdge(float dy) {
        return dy > 0 ? !canScrollVertically(-1) : !canScrollVertically(1);
    }

    private void start(float from) {
        pulling = true;
        raw = from;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
    }

    private void settle(float v) {
        if (motion != null) motion.cancel();
        if (offset == 0) return;
        motion = Spring.PAGE.fling(offset, 0, v, this::setOffset, null);
    }

    private void setOffset(float v) {
        if (v == offset) return;
        offset = v;
        boolean own = listener != null && listener.pulled(v);
        View child = getChildCount() > 0 ? getChildAt(0) : null;
        if (child != null) child.setTranslationY(own ? 0 : v);
    }

    /** 橡皮筋：手指拉了 x，内容只移动这么多，越远越拉不动，最多接近整个高度（同 PullLayout.band，带方向）。 */
    private float band(float x) {
        float d = Math.max(1, getHeight()), a = Math.abs(x);
        return Math.signum(x) * (1 - 1 / (a * 0.55f / d + 1)) * d;
    }

    /** band 的反函数。 */
    private float unband(float y) {
        float d = Math.max(1, getHeight()), a = Math.min(Math.abs(y), d * 0.99f);
        return Math.signum(y) * d / 0.55f * (1 / (1 - a / d) - 1);
    }

    /** band 在 x 处的斜率：手指每移动 1，内容移动多少。 */
    private float slope(float x) {
        float d = Math.max(1, getHeight()), k = Math.abs(x) * 0.55f / d + 1;
        return 0.55f / (k * k);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (motion != null) motion.cancel();
        super.onDetachedFromWindow();
    }
}
