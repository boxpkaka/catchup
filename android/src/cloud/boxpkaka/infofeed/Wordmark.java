package cloud.boxpkaka.infofeed;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * 首页左上角的字标：左边是和桌面图标同一个标记（钴蓝雾面金属的底、珍珠收笔的 c，静止，预先渲染好的
 * logo_mark.png），带金属的倒角和一点投影；右边是 Great Vibes 的 catchup（矢量 wordmark.xml），墨色。
 * 尺寸照设计原型：标记 26dp、圆角 8dp，隔 6dp 接字，字号 37dp，基线比标记的中线低 6.5dp。
 */
final class Wordmark extends View {
    private static final float MARK = 26, RADIUS = 8, GAP = 6, HEIGHT = 38, BASELINE = 6.5f;
    // wordmark.xml 的画布左上角相对字的起点和基线的位置（dp），见那个文件的注释
    private static final float TEXT_X = 0.37f, TEXT_TOP = -23.57f;

    private final Theme t;
    private final Bitmap mark;
    private final Drawable text;
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF(), shade = new RectF();
    private final Rect bounds = new Rect(), src = new Rect();

    Wordmark(Context c, Theme t) {
        super(c);
        this.t = t;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        mark = BitmapFactory.decodeResource(c.getResources(), R.drawable.logo_mark, o);
        src.set(0, 0, mark.getWidth(), mark.getHeight());
        text = c.getDrawable(R.drawable.wordmark).mutate();
        text.setTint(t.ink);
        // 投影：和原型的 0 2px 6px -2px rgba(10,14,60,.45) 一样，往里收 2dp、往下 2dp
        shadow.setColor(0xFF2B2F7A);
        shadow.setShadowLayer(t.dp(5), 0, t.dp(2), 0x730A0E3C);
        setContentDescription("Catchup");
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = t.dp(MARK + GAP + TEXT_X) + text.getIntrinsicWidth();
        setMeasuredDimension(resolveSize(w, widthSpec), resolveSize(t.dp(HEIGHT), heightSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float cy = h / 2f, r = t.dp(RADIUS);
        box.set(0, cy - t.dp(MARK) / 2f, t.dp(MARK), cy + t.dp(MARK) / 2f);
        shade.set(box);
        shade.inset(t.dp(2), t.dp(2));
        clip.reset();
        clip.addRoundRect(box, r, r, Path.Direction.CW);
        box.round(bounds);
        int left = Math.round(box.right + t.dp(GAP + TEXT_X)), top = Math.round(cy + t.dp(BASELINE + TEXT_TOP));
        text.setBounds(left, top, left + text.getIntrinsicWidth(), top + text.getIntrinsicHeight());
    }

    @Override
    protected void onDraw(Canvas c) {
        float r = t.dp(RADIUS);
        c.drawRoundRect(shade, r, r, shadow);
        int save = c.save();
        c.clipPath(clip);
        c.drawBitmap(mark, src, box, image);
        c.restoreToCount(save);
        t.metalFinish(c, bounds, r);
        text.draw(c);
    }
}
