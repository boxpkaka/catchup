package cloud.boxpkaka.infofeed;

/**
 * 一种风格的动效（见 Style）：按用途的弹簧，以及点开卡片时是原地展开还是从右边推进来。
 * 动效都向 Motion.now() 要，不直接写弹簧的参数；某个控件自己的小动画（心形冒出来、提示淡入）跟着它所在的版式走。
 */
final class Motion {
    /**
     * 雾面金属：位置和形状用会轻微回弹的弹簧，颜色和透明度用不回弹的，点开的卡片原地长成新页面。
     */
    static final Motion SPRINGY = new Motion(new Spring(1400, 0.6), new Spring(700, 0.75), new Spring(900, 1),
            new Spring(380, 0.95), new Spring(260, 0.84), true);
    /** 朴素（验证用）：一律不回弹，慢一点；点开都从右边推进来。 */
    static final Motion CALM = new Motion(new Spring(900, 1), new Spring(420, 1), new Spring(600, 1),
            new Spring(380, 1), new Spring(300, 1), false);

    /** 按压、开关、勾选。 */
    final Spring fast;
    /** 卡片飞出、撤销条升起。 */
    final Spring std;
    /** 颜色、透明度。 */
    final Spring fx;
    /** 翻页、推入推出、松手归位：接着手指的速度走。 */
    final Spring page;
    /** 卡片展开、收拢，面板弹出。 */
    final Spring card;
    /** 点开卡片时原地长成新页面（Card）；false 时从右边推进来（Push）。 */
    final boolean expand;

    private Motion(Spring fast, Spring std, Spring fx, Spring page, Spring card, boolean expand) {
        this.fast = fast;
        this.std = std;
        this.fx = fx;
        this.page = page;
        this.card = card;
        this.expand = expand;
    }

    /** 当前风格的动效。 */
    static Motion now() {
        return Style.current().motion;
    }
}
