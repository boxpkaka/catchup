package cloud.boxpkaka.infofeed;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.View;

import java.util.Random;

/**
 * 像 Apple Music 播放页那样流动的封面颜色：一张模糊过的小封面画三遍，各自绕不同的中心慢慢转、缩放、漂移（来回往复，缓入缓出），
 * 上面盖一层遮罩（字压在上面也看得清）和一层很淡的颗粒。封面还没下来时画两色渐变，下来后淡入。
 * 首页最值得看的卡片用它当底（圆角跟着卡片背景，右滑时一起变）；详情页头部用它往下淌进白色页面（bleed）：
 * 封面哪一侧更深、更饱和，颜色就从那一侧淌得更深、拖出长尾巴，另一侧早早收住，边界也慢慢变形。
 * 动画按全局时钟加每件事的错开量算，同一件事的卡片和详情页走在同一个位置，展开时接得上；系统关了动画时静止。
 * 圆角靠 clipPath 而不是轮廓裁剪，Card 用 View.draw 截图时也是圆角。
 */
final class Flow extends View {
    /** 三层：时长（秒）、透明度、转动中心（占这一层的比例）、缩放起止、转角、平移（占这一层的比例）。 */
    private static final float[][] LAYERS = {
            {8, 1f, .5f, .5f, 1f, 1.32f, 150, .14f, -.10f},
            {11, .72f, .24f, .32f, 1.14f, 1.4f, -190, -.16f, .12f},
            {14, .6f, .76f, .68f, 1.26f, 1.02f, 220, .15f, .14f},
    };
    private static final long BLEED_MS = 9000, FADE_MS = 400;
    private static final LruCache<String, Bitmap> BLURRED = new LruCache<>(64);
    private static Bitmap noise;

    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(), veil = new Paint(), grain = new Paint(), shape = new Paint();
    private final Paint dstIn = new Paint();
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final Rect visible = new Rect();
    private final Path clip = new Path();
    private final float density;
    private Bitmap blur;
    private int base, top, bottom;
    private boolean dark, card;
    private long offset, shownAt;
    private View corners;
    private float[][] bleed; // 每团：中心 x、离底边的距离、半径 x、半径 y、漂移 x、漂移 y、透明度
    private int shadedH = -1;

    Flow(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        dstIn.setBlendMode(BlendMode.DST_IN);
        grain.setBlendMode(BlendMode.OVERLAY);
        if (noise == null) noise = noise();
        BitmapShader g = new BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        Matrix gm = new Matrix();
        gm.setScale(density * 0.6f, density * 0.6f); // 颗粒约 2 个像素，太细的在高分屏上看不出来
        g.setLocalMatrix(gm);
        grain.setShader(g);
    }

    /** 做过的模糊小图，没做过返回 null。 */
    static Bitmap cachedBlur(String key) {
        return BLURRED.get(key);
    }

    /** 封面缩成 48×27、横竖各模糊三遍的小图；同一张只做一次。 */
    static Bitmap blurred(String key, Bitmap cover) {
        Bitmap b = BLURRED.get(key);
        if (b != null) return b;
        int w = 48, h = 27;
        int[] px = new int[w * h];
        Bitmap.createScaledBitmap(cover, w, h, true).getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            px = box(px, w, h, 3, true);
            px = box(px, w, h, 3, false);
        }
        b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        BLURRED.put(key, b);
        return b;
    }

    private static int[] box(int[] in, int w, int h, int r, boolean horizontal) {
        int[] out = new int[in.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rs = 0, gs = 0, bs = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = horizontal ? Math.max(0, Math.min(w - 1, x + k)) : x;
                    int yy = horizontal ? y : Math.max(0, Math.min(h - 1, y + k));
                    int c = in[yy * w + xx];
                    rs += Color.red(c);
                    gs += Color.green(c);
                    bs += Color.blue(c);
                    n++;
                }
                out[y * w + x] = Color.rgb(rs / n, gs / n, bs / n);
            }
        }
        return out;
    }

    /** 封面下半张哪边更“重”（更深、更饱和）：-1 是左边，1 是右边，0 是差不多。 */
    static float lean(Bitmap cover) {
        int w = 32, h = 18;
        int[] px = new int[w * h];
        Bitmap.createScaledBitmap(cover, w, h, true).getPixels(px, 0, w, 0, 0, w, h);
        float left = 0, right = 0;
        float[] hsv = new float[3];
        for (int y = h / 2; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                Color.colorToHSV(c, hsv);
                float weight = 1 - (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255
                        + 0.5f * hsv[1] * hsv[2];
                if (x < w / 2) left += weight;
                else right += weight;
            }
        }
        return left + right == 0 ? 0 : (right - left) / (right + left);
    }

    /**
     * 先画的两色渐变（封面还没下来，或者没有封面）。dark 是深底白字，遮罩用压暗的；card 是首页卡片，遮罩上下一样，
     * 否则是详情页头部，遮罩上深下浅。id 决定错开量。
     */
    void gradient(int top, int bottom, boolean dark, boolean card, long id) {
        this.top = top;
        this.bottom = bottom;
        this.dark = dark;
        this.card = card;
        offset = id % 23 * 1000;
        blur = null;
        shadedH = -1;
        invalidate();
    }

    /** 换成流动的模糊封面：fade 为 true 时从渐变淡入（图是刚下来的），否则直接显示。 */
    void cover(Bitmap blurred, boolean fade) {
        if (blurred == blur) return;
        blur = blurred;
        base = Bitmap.createScaledBitmap(blurred, 1, 1, true).getPixel(0, 0);
        shownAt = fade ? SystemClock.uptimeMillis() : 0;
        invalidate();
    }

    /** 圆角跟着 v 的背景（首页卡片：右滑时背景的圆角会变）。 */
    void corners(View v) {
        corners = v;
    }

    /**
     * 往下淌的形状：一大团在 lean 那一侧拖出长尾巴，一小团在另一侧早收住，中间再渗下来一小处，底下一段渐隐兜住标题；
     * lean 接近 0 时按 id 随机选一侧。形状只定一次，之后只是慢慢漂。
     */
    void bleed(float lean, long id) {
        long x = id % 233280;
        float[] r = new float[12];
        for (int i = 0; i < r.length; i++) {
            x = (x * 9301 + 49297) % 233280;
            r[i] = x / 233280f;
        }
        if (Math.abs(lean) < .1f) lean = (r[0] < .5f ? -1 : 1) * (.25f + r[1] * .3f);
        float side = lean > 0 ? 1 : -1, k = Math.min(1, Math.abs(lean) * 2.5f);
        bleed = new float[][]{
                {.5f + side * (.2f + k * .14f), 370, (.58f + r[2] * .14f) * 1.3f, 220 + r[3] * 50, (r[4] - .5f) * 110, (r[5] - .3f) * 90, .92f},
                {.5f - side * (.26f + r[6] * .12f), 380, (.34f + r[7] * .12f) * 1.3f, 100 + r[8] * 36, (r[9] - .5f) * 110, (r[10] - .3f) * 90, .75f},
                {.5f + side * (r[11] * .16f - .04f), 300, (.16f + r[2] * .1f) * 1.3f, 80 + r[3] * 34, (r[5] - .5f) * 110, (r[4] - .3f) * 90, .4f},
        };
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        boolean moving = ValueAnimator.areAnimatorsEnabled();
        long now = SystemClock.uptimeMillis(), t = (moving ? now : 0) + offset;
        if (h != shadedH) shade(h);
        int save = canvas.save();
        float[] radii = radii();
        if (radii != null) {
            clip.reset();
            rect.set(0, 0, w, h);
            clip.addRoundRect(rect, radii, Path.Direction.CW);
            canvas.clipPath(clip);
        }
        int layer = bleed != null ? canvas.saveLayer(0, 0, w, h, null) : -1;
        canvas.drawRect(0, 0, w, h, fill); // 两色渐变：封面下来前、淡入时垫在下面
        if (blur != null) { // 遮罩只压在模糊封面上，跟着它一起淡入；只有渐变时和卡片本来的颜色一样
            float in = shownAt == 0 || !moving ? 1 : Math.min(1, (now - shownAt) / (float) FADE_MS);
            drawLayers(canvas, w, h, t, in);
            veil.setAlpha(Math.round(255 * in));
            canvas.drawRect(0, 0, w, h, veil);
            if (in < 1) moving = true; // 淡入没完就接着画
        }
        grain.setAlpha(dark ? 41 : 31);
        canvas.drawRect(0, 0, w, h, grain);
        if (bleed != null) {
            canvas.saveLayer(0, 0, w, h, dstIn);
            drawBleed(canvas, w, h, t);
            canvas.restoreToCount(layer);
        }
        canvas.restoreToCount(save);
        if (moving && isShown() && getGlobalVisibleRect(visible)) postInvalidateOnAnimation();
    }

    /** 三层模糊封面：每层是边长为对角线 1.4 倍的正方形，转到什么角度都铺得满。 */
    private void drawLayers(Canvas canvas, int w, int h, long t, float alpha) {
        float side = (float) Math.hypot(w, h) * 1.4f, left = (w - side) / 2, topY = (h - side) / 2;
        image.setColor(base);
        image.setAlpha(Math.round(255 * alpha));
        canvas.drawRect(0, 0, w, h, image);
        for (float[] l : LAYERS) {
            float e = ease(t, (long) (l[0] * 1000));
            float px = left + side * l[2], py = topY + side * l[3];
            matrix.reset();
            matrix.setScale(side / blur.getWidth(), side / blur.getHeight());
            matrix.postTranslate(left + l[7] * side * e, topY + l[8] * side * e);
            float s = l[4] + (l[5] - l[4]) * e;
            matrix.postScale(s, s, px, py);
            matrix.postRotate(l[6] * e, px, py);
            image.setAlpha(Math.round(255 * l[1] * alpha));
            canvas.drawBitmap(blur, matrix, image);
        }
    }

    /** 蒙版：几团径向渐变（压扁成椭圆）叠一段线性渐隐，每团按 BLEED_MS 来回漂。 */
    private void drawBleed(Canvas canvas, int w, int h, long t) {
        float e = ease(t, BLEED_MS);
        for (float[] b : bleed) {
            float cx = w * b[0] + b[4] * density * e, cy = h - b[1] * density + b[5] * density * e;
            float rx = w * b[2], ry = b[3] * density;
            float[] stops = b[6] > .9f ? new float[]{0, .3f, .62f, 1} : new float[]{0, .5f, 1};
            int[] colors = b[6] > .9f
                    ? new int[]{alpha(b[6]), alpha(.55f), alpha(.24f), 0}
                    : new int[]{alpha(b[6]), alpha(b[6] * .4f), 0};
            RadialGradient g = new RadialGradient(0, 0, 1, colors, stops, Shader.TileMode.CLAMP);
            matrix.setScale(rx, ry);
            matrix.postTranslate(cx, cy);
            g.setLocalMatrix(matrix);
            shape.setShader(g);
            canvas.drawRect(cx - rx, cy - ry, cx + rx, cy + ry, shape);
        }
        float d = density;
        shape.setShader(new LinearGradient(0, h - 410 * d, 0, h - 290 * d,
                new int[]{Color.BLACK, alpha(.62f), alpha(.22f), 0}, new float[]{0, .33f, .67f, 1}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h - 290 * d, shape);
    }

    /** 渐变和遮罩的着色器跟着高度变，高度不变就不重做。 */
    private void shade(int h) {
        shadedH = h;
        fill.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
        int[] colors = card
                ? new int[]{dark ? 0x6B08080C : 0x94FFFFFF, dark ? 0x6B08080C : 0x94FFFFFF}
                : dark ? new int[]{0x8C08080C, 0x8008080C, 0x1408080C} : new int[]{0x9EFFFFFF, 0x8CFFFFFF, 0x1FFFFFFF};
        float[] at = card ? null : new float[]{0, .55f, 1};
        veil.setShader(new LinearGradient(0, 0, 0, h, colors, at, Shader.TileMode.CLAMP));
    }

    private float[] radii() {
        if (corners == null) return null;
        Drawable bg = corners.getBackground();
        if (bg instanceof RippleDrawable) bg = ((RippleDrawable) bg).getDrawable(0);
        return bg instanceof GradientDrawable ? ((GradientDrawable) bg).getCornerRadii() : null;
    }

    /** 来回往复、缓入缓出：0 到 1 再回到 0，一个来回 2 × period。 */
    private static float ease(long t, long period) {
        double phase = (double) t / period, frac = phase - Math.floor(phase);
        double x = ((long) Math.floor(phase)) % 2 == 0 ? frac : 1 - frac;
        return (float) ((1 - Math.cos(Math.PI * x)) / 2);
    }

    private static int alpha(float a) {
        return Math.round(a * 255) << 24;
    }

    /** 颗粒：灰度噪点，用 OVERLAY 叠上去，亮的地方提亮、暗的地方压暗。 */
    private static Bitmap noise() {
        int n = 96;
        int[] px = new int[n * n];
        Random rnd = new Random(7);
        for (int i = 0; i < px.length; i++) {
            int v = rnd.nextInt(256);
            px[i] = Color.rgb(v, v, v);
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
    }
}
