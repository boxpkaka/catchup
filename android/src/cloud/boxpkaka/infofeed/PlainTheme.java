package cloud.boxpkaka.infofeed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;

/**
 * 朴素风格（验证用，见 Style）的材质：一律平涂，没有磨砂的倒角和金属的光泽；磨砂的底换成不透明的卡片色，背景不动时也干净。
 * 配色、圆角和字和雾面金属一样。
 */
final class PlainTheme extends Theme {
    PlainTheme(Context c) {
        super(c);
    }

    @Override
    GradientDrawable frost(int fill, float tl, float tr, float br, float bl) {
        return round(fill == frost || fill == frostButton ? sheet : fill, tl, tr, br, bl);
    }

    @Override
    GradientDrawable metal(int base, float radiusDp) {
        return round(base, radiusDp);
    }

    @Override
    void metalFinish(Canvas c, Rect b, float radiusPx) {
        // 平涂：首页的标记不画倒角
    }

    @Override
    void finish(Spring.Shape shape, int color, boolean metal) {
        // 平涂：按钮不加倒角，也不变成金属
    }
}
