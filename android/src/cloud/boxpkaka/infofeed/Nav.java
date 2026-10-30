package cloud.boxpkaka.infofeed;

import android.app.Activity;
import android.view.View;

/**
 * 二级页面怎么进来、怎么回去：从点开的那张卡片展开（Card），或者从右边推进来（Push）。
 * 用法：onCreate 里 nav = Nav.of(this, 页面, 底色)，setContentView(nav.wrap(state == null))；
 * finish() 里调用 nav.finish(super::finish)，返回键、返回按钮和代码里的 finish() 就都先播完动画再关。
 */
interface Nav {
    /** 打开的一方用 Card.open 交接了卡片就从卡片展开，否则从右边推进来。 */
    static Nav of(Activity a, View page, int bg) {
        Card card = Card.take(a, page, bg);
        return card != null ? card : new Push(a, page, bg);
    }

    /** 只能右滑返回的页面：整页包起来；fresh（不是转屏之类的重建）时播放进场动画。 */
    Swipe wrap(boolean fresh);

    /** 进场动画。自己包手势的页面（专题页）把 page 放进外框以后调用。 */
    void enter();

    /** 还能不能右滑返回：已经在离开了就不行。 */
    boolean can();

    void grab();

    /** dx 是开始拖动以来往右滑了多远。 */
    void drag(float dx);

    /** 松手：vx 是横向速度（像素/秒，右为正），过线或者往右甩就返回，否则弹回。 */
    void release(float vx);

    /** 在 Activity.finish() 里调用，done 是 super.finish()：先动画再关，已经动完了就直接关。 */
    void finish(Runnable done);

    /** 下一页已经盖上来了，这一页直接关，不用动画。 */
    void replaced();
}
