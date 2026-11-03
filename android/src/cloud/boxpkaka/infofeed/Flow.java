package cloud.boxpkaka.infofeed;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.View;

import java.util.Arrays;

/**
 * 雾面金属（见 docs/design.md）：像 Apple Music 播放页那样流动的颜色，过一条“金属曲线”，再加雾面颗粒。
 * 一张图（模糊过的封面，或者按话题生成的抽象封面，都是 192×108，见 art）画三遍，各自绕不同的中心慢慢转、缩放、漂移
 * （来回往复，缓入缓出）；每个点按亮度过一条“金属曲线”：在这张图自己的亮度范围里，66% 那一档起一道窄的亮脊，32% 那一档
 * 压暗一点，颜色流动时亮脊沿着等高线滑动、变形，像流动的金属，色相不变。图的四周按边上的颜色往外延（CLAMP），转到什么角度
 * 都不露直边。再盖遮罩和颗粒。
 * 安卓 13 起整个在 GPU 上按屏幕像素逐点算（Gpu，RuntimeShader），没有格子、没有放大的痕迹，也不占主线程；
 * 老系统在 4dp 一格的小位图上算（compute），亮度用小数、和上一帧平滑，放大画上去；新系统上软件画布（卡片展开前拍的截图）
 * 也走这一路，算法相同，颜色接得上。
 * 三种用法：
 * - 卡片（首页“最值得看”、专题卡片、专题标题栏、报告的“要点”）：盖一层遮罩（字压在上面也看得清），边上一圈细倒角，
 *   圆角跟着卡片的背景（右滑时背景的圆角会变）；
 * - 详情页头部（bleed）：比头部长出一截，往下淌成一团雾。深的一层在标题下面化开，浅的一层（同一张图的亮色版本）淌进正文；
 *   雾的边由“淌到多深”的起伏和一张流动的噪声决定，一团团、一缕缕，没有固定的轮廓；
 * - 页面的雾（mist）：每一页最底下，同一页颜色的亮色版本盖一层白（深色模式压暗），流得是卡片的三分之一快。
 * 颗粒见 Grain。动画按全局时钟加每件事的错开量算，同一件事的卡片和详情页走在同一个位置，展开时接得上；系统关了动画时静止。
 * 圆角靠 clipPath 而不是轮廓裁剪，Card 用 View.draw 截图时也是圆角。
 */
final class Flow extends View {
    /** 三层：时长（秒）、透明度、转动中心（占这一层的比例）、缩放起止、转角、平移（占这一层的比例）。 */
    private static final float[][] LAYERS = {
            {8, 1f, .5f, .5f, 1f, 1.32f, 150, .14f, -.10f},
            {11, .72f, .24f, .32f, 1.14f, 1.4f, -190, -.16f, .12f},
            {14, .6f, .76f, .68f, 1.26f, 1.02f, 220, .15f, .14f},
    };
    private static final int CARD = 0, HEAD = 1, MIST = 2;
    private static final float CELL_DP = 4, MIST_CELL_DP = 8; // 小位图一格多少 dp：页面的雾很软，格子大一倍
    private static final long FRAME_MS = 33, MIST_FRAME_MS = 66, FADE_MS = 400;
    private static final LruCache<String, Bitmap> BLURRED = new LruCache<>(64), ARTS = new LruCache<>(48);
    private static final LruCache<Bitmap, Bitmap> LIFTED = new LruCache<>(32);
    private static final LruCache<Bitmap, Look[]> LOOKS = new LruCache<>(96);
    private static final boolean GPU = Build.VERSION.SDK_INT >= 33;
    private static final long START = SystemClock.uptimeMillis(); // GPU 上雾的噪声按进程启动以来的秒数算，数小，精度够

    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG), layer = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint veil = new Paint(), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dstIn = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final Rect visible = new Rect(), dst = new Rect();
    private final Path clip = new Path();
    private final float density;
    private final boolean night;
    private final Grain grain;
    private int mode = CARD;
    private boolean dark, dirty = true;
    private int accent;
    private long offset, fadeFrom;
    private float slow = 1;
    private Bitmap deep, light, fallback; // 颜色来源：深的一层；详情页往下淌的浅雾；来源下来前的两色渐变
    private View corners;
    // 详情页的雾：颜色重的一侧（1 右、-1 左）、深浅差、两种起伏的相位、噪声的错开量；往下淌的长度（像素）
    private float side = 1, k, r0, r1, fogOff;
    private int tail;
    // 小位图：sw × sh 格。work 上画三层，算好的结果平滑以后放进 out，放大画上去
    private int sw, sh;
    private Bitmap work, out, mask; // mask：详情页头部的雾有多浓（只用透明度），out 是不透明的颜色
    private Canvas workCanvas;
    private int[] px, px2, res, resMask;
    private float[] acc, tmp, n1, n2;
    private Gpu shade, before; // GPU：现在的这一套；换了来源、正在淡入时，之前的那一套垫在下面
    private final float[] values = new float[9];
    private long computedAt = -1, frameMs = FRAME_MS; // 多久重算一次：按上一次算了多久定，主线程最多花四分之一在这上面
    private int rimH = -1;

    Flow(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        dstIn.setBlendMode(BlendMode.DST_IN);
        grain = new Grain(c);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2 * density); // 一半在形状外面被裁掉，露出 1dp
    }

    /**
     * 雾面颗粒：一张 512×512、可以无缝平铺的胶片颗粒（res/drawable-nodpi/grain.png，见 tools/grain.py），颗粒边缘是柔的，
     * 一格就是一个屏幕像素，原样取样，不放大、不插值，所以既不会是一格一格的方块，也不发虚。拆成亮粒（带透明度的白）和暗粒（带透明度的黑）两张，
     * 普通叠加，不用 OVERLAY 这种各家 GPU 表现不一、还要回读底色的混合模式；两张的强度按底色的亮度配比（set），
     * 效果接近 OVERLAY：深底上主要是暗粒在压、亮粒轻一些，浅底上反过来，颗粒都是随着底色起伏，而不是一层白点或黑点。
     */
    static final class Grain {
        private static BitmapShader lightShader, darkShader, grayShader; // gray：GPU 上直接读灰度（128 是不变）
        private final Paint light = new Paint(Paint.FILTER_BITMAP_FLAG), dark = new Paint(Paint.FILTER_BITMAP_FLAG);
        private Grain lower; // fade：下面一段，按自己的底色配比
        private float fadeFrom = Float.NaN, fadeTo;

        Grain(Context c) {
            if (lightShader == null) load(c.getResources());
            light.setShader(lightShader);
            dark.setShader(darkShader);
        }

        private static void load(Resources r) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            Bitmap g = BitmapFactory.decodeResource(r, R.drawable.grain, o);
            int w = g.getWidth(), h = g.getHeight();
            int[] p = new int[w * h], up = new int[w * h], down = new int[w * h];
            g.getPixels(p, 0, w, 0, 0, w, h);
            for (int i = 0; i < p.length; i++) {
                int v = (p[i] & 0xFF) - 128; // 128 是不变，往上是亮粒、往下是暗粒
                up[i] = Math.min(255, Math.max(0, v) * 2) << 24 | 0xFFFFFF;
                down[i] = Math.min(255, Math.max(0, -v) * 2) << 24;
            }
            lightShader = shader(Bitmap.createBitmap(up, w, h, Bitmap.Config.ARGB_8888));
            darkShader = shader(Bitmap.createBitmap(down, w, h, Bitmap.Config.ARGB_8888));
            grayShader = shader(g);
        }

        private static BitmapShader shader(Bitmap b) {
            return new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        }

        /** 亮粒、暗粒各自的强度（0 到 1）：strength 是颗粒有多重，base 是底色大致的亮度（0 到 1）。 */
        static float[] weights(float strength, float base) {
            float b = Math.max(.05f, Math.min(.95f, base));
            return new float[]{strength * (b < .5f ? b / (1 - b) : 1), strength * (b < .5f ? 1 : (1 - b) / b)};
        }

        Grain set(float strength, float base) {
            float[] w = weights(strength, base);
            light.setAlpha(Math.round(255 * w[0]));
            dark.setAlpha(Math.round(255 * w[1]));
            return this;
        }

        void draw(Canvas c, float l, float t, float r, float b) {
            c.drawRect(l, t, r, b, light);
            c.drawRect(l, t, r, b, dark);
        }

        /**
         * 上下两段底色不一样（详情页头部：上面是标题底下的深色，往下是雾）：从 y0 到 y1 渐渐从上面的配比换成下面的配比，
         * 不出接缝。top、bottom 是两段底色的亮度。
         */
        void drawFade(Canvas c, Context ctx, float w, float h, float y0, float y1, float strength, float top, float bottom) {
            if (lower == null) lower = new Grain(ctx);
            if (y0 != fadeFrom || y1 != fadeTo) {
                fadeFrom = y0;
                fadeTo = y1;
                LinearGradient upper = new LinearGradient(0, y0, 0, y1, 0xFF000000, 0, Shader.TileMode.CLAMP);
                LinearGradient below = new LinearGradient(0, y0, 0, y1, 0, 0xFF000000, Shader.TileMode.CLAMP);
                light.setShader(new ComposeShader(upper, lightShader, BlendMode.SRC_IN));
                dark.setShader(new ComposeShader(upper, darkShader, BlendMode.SRC_IN));
                lower.light.setShader(new ComposeShader(below, lightShader, BlendMode.SRC_IN));
                lower.dark.setShader(new ComposeShader(below, darkShader, BlendMode.SRC_IN));
            }
            set(strength, top).draw(c, 0, 0, w, h);
            lower.set(strength, bottom).draw(c, 0, 0, w, h);
        }
    }

    /** 做过的模糊小图，没做过返回 null。 */
    static Bitmap cachedBlur(String key) {
        return BLURRED.get(key);
    }

    /**
     * 封面缩成 192×108、横竖各模糊三遍（半径 12，三遍盒子模糊接近高斯）的图；同一张只做一次。图比要铺的地方小，
     * 但已经很模糊了，放大不会有棱角。
     */
    static Bitmap blurred(String key, Bitmap cover) {
        Bitmap b = BLURRED.get(key);
        if (b != null) return b;
        int w = 192, h = 108;
        int[] px = new int[w * h];
        Bitmap.createScaledBitmap(cover, w, h, true).getPixels(px, 0, w, 0, 0, w, h);
        int[] tmp = new int[w * h];
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, w, h, 12, true);
            box(tmp, px, w, h, 12, false);
        }
        b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        BLURRED.put(key, b);
        return b;
    }

    /** 盒子模糊一遍（横或竖）：滑动窗口累加，边上按最边上的颜色往外延。 */
    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h, n = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int rs = 0, gs = 0, bs = 0;
            for (int k = -r; k <= r; k++) {
                int c = in[at(line, Math.max(0, Math.min(len - 1, k)), w, horizontal)];
                rs += (c >> 16) & 0xFF;
                gs += (c >> 8) & 0xFF;
                bs += c & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[at(line, i, w, horizontal)] = 0xFF000000 | (rs / n) << 16 | (gs / n) << 8 | (bs / n);
                int add = in[at(line, Math.min(len - 1, i + r + 1), w, horizontal)];
                int drop = in[at(line, Math.max(0, i - r), w, horizontal)];
                rs += ((add >> 16) & 0xFF) - ((drop >> 16) & 0xFF);
                gs += ((add >> 8) & 0xFF) - ((drop >> 8) & 0xFF);
                bs += (add & 0xFF) - (drop & 0xFF);
            }
        }
    }

    private static int at(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    /** 浅色模式的浅雾用的封面：提亮、加饱和、去灰，冲淡了也是干净的同色，不发灰。 */
    static Bitmap lifted(Bitmap blurred) {
        Bitmap b = LIFTED.get(blurred);
        if (b != null) return b;
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(2.2f);
        ColorMatrix bright = new ColorMatrix();
        bright.setScale(1.55f, 1.55f, 1.55f, 1);
        cm.postConcat(bright);
        float c = 0.8f, o = (1 - c) * 127.5f;
        cm.postConcat(new ColorMatrix(new float[]{c, 0, 0, 0, o, 0, c, 0, 0, o, 0, 0, c, 0, o, 0, 0, 0, 1, 0}));
        b = Bitmap.createBitmap(blurred.getWidth(), blurred.getHeight(), Bitmap.Config.ARGB_8888);
        Paint p = new Paint();
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        new Canvas(b).drawBitmap(blurred, 0, 0, p);
        LIFTED.put(blurred, b);
        return b;
    }

    /**
     * 没有封面时的“抽象封面”：192×108 的图（都是很柔的渐变，放大也没有棱角；再大的话在主线程上生成太慢），话题色系的六种颜色（见 Theme.artColors）铺成七团径向渐变，位置按 seed 定。
     * 同一件事（seed 是事件 id）的卡片和详情页、同一个专题（seed 是 seedOf）的卡片和专题页是同一张。pastel 是只有亮色的那张。
     */
    static Bitmap art(Theme t, String key, long seed, boolean pastel) {
        String k = key + "|" + seed + "|" + t.night + "|" + pastel;
        Bitmap b = ARTS.get(k);
        if (b != null) return b;
        int w = 192, h = 108;
        b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int[] cols = t.artColors(key, pastel);
        c.drawColor(cols[0]);
        float[] r = seq(seed, 24);
        float[][] blobs = {{1, .42f}, {2, .36f}, {3, .36f}, {4, .24f}, {5, .22f}, {1, .2f}, {2, .18f}};
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        for (int i = 0; i < blobs.length; i++) {
            int col = cols[(int) blobs[i][0]] & 0xFFFFFF;
            float x = r[i * 3] * w, y = r[i * 3 + 1] * h, rad = (blobs[i][1] + r[i * 3 + 2] * .14f) * w;
            p.setShader(new RadialGradient(x, y, rad, new int[]{col | 0xF2000000, col | 0x80000000, col},
                    new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
        }
        ARTS.put(k, b);
        return b;
    }

    /** 专题的 seed：同一个专题在专题卡片和专题页是同一张抽象封面。 */
    static long seedOf(String key) {
        return Math.floorMod(key.hashCode(), 9973);
    }

    /** 一串 0 到 1 的伪随机数，按 id 定。 */
    private static float[] seq(long id, int n) {
        long x = Math.floorMod(id, 233280L);
        float[] r = new float[n];
        for (int i = 0; i < n; i++) {
            x = (x * 9301 + 49297) % 233280;
            r[i] = x / 233280f;
        }
        return r;
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
     * 卡片和详情页头部先用的颜色：两色渐变（封面还没下来时），来源到了再慢慢过渡过去。dark 是深底白字；card 是卡片，
     * 否则是详情页头部（再调 bleed）。id 决定错开量。
     */
    void gradient(int top, int bottom, boolean dark, boolean card, long id) {
        this.dark = dark;
        mode = card ? CARD : HEAD;
        offset = id % 23 * 1000;
        deep = light = null;
        fallback = Bitmap.createBitmap(new int[]{top, bottom}, 1, 2, Bitmap.Config.ARGB_8888);
        refresh(false);
    }

    /** 换成流动的模糊封面：fade 为 true 时从现在的颜色慢慢过渡过去（图是刚下来的），否则直接显示。 */
    void cover(Bitmap blurred, boolean fade) {
        if (blurred == deep) return;
        deep = blurred;
        light = night ? blurred : lifted(blurred);
        refresh(fade);
    }

    /** 没有封面：用话题（或主色、珍珠银）生成的抽象封面，seed 见 art。 */
    void topic(Theme t, String key, long seed) {
        deep = art(t, key, seed, false);
        light = art(t, key, seed, !night);
        refresh(false);
    }

    /** 页面的雾：话题色的亮色版本（深色模式是压暗的本色），流得是卡片的三分之一快。 */
    void mist(Theme t, String key, long seed) {
        mode = MIST;
        dark = night;
        slow = 3;
        offset = seed % 23 * 1000;
        deep = art(t, key, seed, !night);
        refresh(false);
    }

    /** 页面的雾换成一张封面（从有封面的卡片打开的详情页）。 */
    void mistCover(Bitmap blurred, boolean fade) {
        Bitmap b = night ? blurred : lifted(blurred);
        if (b == deep) return;
        deep = b;
        refresh(fade);
    }

    /** 流动放慢的倍数（专题页一屏七张卡片，各自慢一半）。 */
    void slow(float factor) {
        slow = factor;
    }

    /** 圆角跟着 v 的背景（首页卡片：右滑时背景的圆角会变）。 */
    void corners(View v) {
        corners = v;
    }

    /**
     * 详情页头部往下淌：比头部长出 tailPx；颜色重的一侧（封面下半张哪边更重，见 lean；接近 0 时按 id 随机选一侧）淌得更深。
     * accent 是卡片的点缀色：浅色模式下往下淌的那段，深色盖的遮罩换成它，深色块化开时是透亮的同色而不是灰。
     */
    void bleed(float lean, long id, int tailPx, int accent) {
        float[] r = seq(id, 2);
        if (Math.abs(lean) < .1f) lean = (r[0] < .5f ? -1 : 1) * (.25f + r[1] * .3f);
        side = lean > 0 ? 1 : -1;
        k = Math.min(1, Math.abs(lean) * 2.5f);
        float[] q = seq(id * 7 + 5, 2);
        r0 = q[0];
        r1 = q[1];
        fogOff = id % 7;
        tail = tailPx;
        this.accent = accent;
        mode = HEAD;
        refresh(false);
    }

    private void refresh(boolean fade) {
        fadeFrom = fade ? SystemClock.uptimeMillis() : 0;
        if (!fade) acc = null; // 不过渡：下一帧直接是新的
        dirty = true;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        boolean moving = ValueAnimator.areAnimatorsEnabled();
        long now = SystemClock.uptimeMillis();
        boolean fading = moving && fadeFrom > 0 && now - fadeFrom < FADE_MS;
        if (GPU && canvas.isHardwareAccelerated()) { // 软件画布（Card 给卡片拍截图）不支持 RuntimeShader，那一帧走下面的小位图
            drawGpu(canvas, w, h, (long) ((moving ? now : 0) / slow) + offset, (moving ? now - START : 0) / 1000f + offset / 1000f,
                    fading ? (now - fadeFrom) / (float) FADE_MS : 1);
            if ((moving || fading) && isShown() && getGlobalVisibleRect(visible)) {
                postInvalidateDelayed(mode == MIST ? FRAME_MS : 16); // 最多六十帧，不跟着高刷屏跑满；页面的雾流得慢，三十帧够了
            }
            return;
        }
        if (size(w, h) || dirty || (moving && now - computedAt >= frameMs)) {
            long t = (long) ((moving ? now : 0) / slow) + offset, started = SystemClock.elapsedRealtime();
            compute(t, now, moving);
            frameMs = Math.max(mode == MIST ? MIST_FRAME_MS : FRAME_MS, (SystemClock.elapsedRealtime() - started) * 4);
            computedAt = now;
            dirty = false;
        }
        int save = canvas.save();
        float[] radii = mode == CARD ? radii() : null;
        if (radii != null) {
            clip.reset();
            rect.set(0, 0, w, h);
            clip.addRoundRect(rect, radii, Path.Direction.CW);
            canvas.clipPath(clip);
        }
        float cell = cell() * density;
        dst.set(0, 0, Math.round(sw * cell), Math.round(sh * cell));
        if (mode == HEAD) { // 颗粒先叠在不透明的颜色上（随着底色起伏），再整个按雾的浓淡淡出
            int layer = canvas.saveLayer(0, 0, w, h, null);
            canvas.drawBitmap(out, null, dst, image);
            float foot = h - tail; // 头部的底边：上面是标题底下的颜色，往下是雾（浅色模式是同色的浅色，深色模式还是深的）
            grain.drawFade(canvas, getContext(), w, h, foot - 40 * density, foot + 140 * density, .15f,
                    dark ? .3f : .85f, night ? .25f : .82f);
            canvas.drawBitmap(mask, null, dst, dstIn);
            canvas.restoreToCount(layer);
        } else {
            canvas.drawBitmap(out, null, dst, image);
            veil.setColor(mode == MIST ? (night ? 0xBD101117 : 0x99FFFFFF) : dark ? 0x6B08080C : 0x94FFFFFF);
            canvas.drawRect(0, 0, w, h, veil);
            if (mode == MIST) grain.set(night ? .16f : .13f, night ? .1f : .92f);
            else grain.set(.15f, dark ? .32f : .86f);
            grain.draw(canvas, 0, 0, w, h);
            if (radii != null) drawRim(canvas, w, h);
        }
        canvas.restoreToCount(save);
        if ((moving || fading) && isShown() && getGlobalVisibleRect(visible)) postInvalidateOnAnimation();
    }

    /**
     * GPU 上画一帧：t 是流动的时钟（毫秒），ts 是雾的噪声用的秒数，in 是新来源淡入到哪了（1 是淡完）。
     * 换了来源（图下来了、颜色定了）时新建一套；要淡入的，旧的那套先画在下面，新的按 in 盖上去。
     */
    private void drawGpu(Canvas canvas, int w, int h, long t, float ts, float in) {
        Bitmap src = deep != null ? deep : fallback, src2 = mode == HEAD && light != null ? light : src;
        if (src == null) return;
        if (shade == null || shade.deep != src || shade.light != src2) {
            before = shade != null && fadeFrom > 0 ? shade : null;
            shade = new Gpu(getContext(), src, src2);
        }
        if (in >= 1) before = null;
        int save = canvas.save();
        float[] radii = mode == CARD ? radii() : null;
        if (radii != null) {
            clip.reset();
            rect.set(0, 0, w, h);
            clip.addRoundRect(rect, radii, Path.Direction.CW);
            canvas.clipPath(clip);
        }
        if (before != null) {
            uniforms(before, w, h, t, ts);
            before.paint.setAlpha(255);
            canvas.drawRect(0, 0, w, h, before.paint);
        }
        uniforms(shade, w, h, t, ts);
        shade.paint.setAlpha(Math.round(255 * Math.min(1, in)));
        canvas.drawRect(0, 0, w, h, shade.paint);
        if (radii != null) drawRim(canvas, w, h);
        canvas.restoreToCount(save);
    }

    /** 这一帧的参数：三层各自从屏幕像素到图上像素的变换、两张图的金属曲线、遮罩、颗粒，详情页头部再加雾。 */
    private void uniforms(Gpu g, int w, int h, long t, float ts) {
        RuntimeShader s = g.shader;
        int bh = mode == HEAD ? Math.min(h, Math.round(w * .72f)) : h; // 详情页头部按卡片的比例摆，见 flow
        float side = (float) Math.hypot(w, bh) * 1.4f, left = (w - side) / 2, top = (bh - side) / 2;
        float[] v = values;
        for (int i = 0; i < LAYERS.length; i++) {
            float[] l = LAYERS[i];
            float e = ease(t, (long) (l[0] * 1000));
            float px = left + side * l[2], py = top + side * l[3], sc = l[4] + (l[5] - l[4]) * e;
            matrix.reset();
            matrix.setScale(side / g.deep.getWidth(), side / g.deep.getHeight());
            matrix.postTranslate(left + l[7] * side * e, top + l[8] * side * e);
            matrix.postScale(sc, sc, px, py);
            matrix.postRotate(l[6] * e, px, py);
            matrix.invert(matrix); // 反过来：屏幕上的点在图上的哪
            matrix.getValues(v);
            s.setFloatUniform("ax" + i, v[0], v[1], v[2]);
            s.setFloatUniform("ay" + i, v[3], v[4], v[5]);
        }
        s.setFloatUniform("scale2", g.light.getWidth() / (float) g.deep.getWidth(), g.light.getHeight() / (float) g.deep.getHeight());
        Look a = look(g.deep, mode == MIST ? (night ? 2 : 0) : dark ? (night ? 2 : 1) : 0), b = look(g.light, night ? 2 : 0);
        s.setFloatUniform("look", a.lo, a.span, a.cap, 0);
        s.setFloatUniform("look2", b.lo, b.span, b.cap, 0);
        if (mode == HEAD) {
            float foot = h - tail; // 颗粒：标题底下按深色配比，往下渐渐换成雾的配比，见 Grain.drawFade
            float[] up = Grain.weights(.15f, dark ? .3f : .85f), down = Grain.weights(.15f, night ? .25f : .82f);
            s.setFloatUniform("grainW", up[0], up[1], down[0], down[1]);
            s.setFloatUniform("grainY", foot - 40 * density, foot + 140 * density);
            s.setFloatUniform("head", 1);
            s.setFloatUniform("fogA", density, foot / density, w, ts);
            s.setFloatUniform("fogB", side, k, r0, r1);
            s.setFloatUniform("fogC", fogOff, dark ? 1 : 0, night ? 1 : 0, 0);
            s.setFloatUniform("accent", Color.red(accent) / 255f, Color.green(accent) / 255f, Color.blue(accent) / 255f);
            s.setFloatUniform("veil", 0, 0, 0, 0);
        } else {
            int c = mode == MIST ? (night ? 0xBD101117 : 0x99FFFFFF) : dark ? 0x6B08080C : 0x94FFFFFF;
            s.setFloatUniform("veil", Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f, Color.alpha(c) / 255f);
            float[] gw = mode == MIST ? Grain.weights(night ? .16f : .13f, night ? .1f : .92f) : Grain.weights(.15f, dark ? .32f : .86f);
            s.setFloatUniform("grainW", gw[0], gw[1], gw[0], gw[1]);
            s.setFloatUniform("grainY", 0, 1);
            s.setFloatUniform("head", 0);
            s.setFloatUniform("fogA", density, 0, w, 0);
            s.setFloatUniform("fogB", 0, 0, 0, 0);
            s.setFloatUniform("fogC", 0, 0, 0, 0);
            s.setFloatUniform("accent", 0, 0, 0);
        }
        g.paint.setShader(s);
    }

    /**
     * GPU 上的一套：一个 RuntimeShader（见 SKSL）和它读的两张图（深的一层、详情页往下淌的浅雾，其余用法两张是同一张）
     * 和颗粒。图按线性插值取样、四周往外延。
     */
    private static final class Gpu {
        final Bitmap deep, light;
        final RuntimeShader shader = new RuntimeShader(SKSL);
        final Paint paint = new Paint();

        Gpu(Context c, Bitmap deep, Bitmap light) {
            this.deep = deep;
            this.light = light;
            if (Grain.grayShader == null) Grain.load(c.getResources());
            shader.setInputShader("src", linear(deep));
            shader.setInputShader("src2", linear(light));
            shader.setInputShader("grain", Grain.grayShader);
        }

        private static BitmapShader linear(Bitmap b) {
            BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            return s;
        }
    }

    /** 和 compute、metal、fog 同一套算法，按屏幕像素逐点算。坐标 p 是视图里的像素。 */
    private static final String SKSL = String.join("\n",
            "uniform shader src;",
            "uniform shader src2;",
            "uniform shader grain;",
            "uniform float3 ax0; uniform float3 ay0; uniform float3 ax1; uniform float3 ay1; uniform float3 ax2; uniform float3 ay2;",
            "uniform float2 scale2;",
            "uniform float4 look;",
            "uniform float4 look2;",
            "uniform float4 veil;",
            "uniform float4 grainW;",
            "uniform float2 grainY;",
            "uniform float head;",
            "uniform float4 fogA;",
            "uniform float4 fogB;",
            "uniform float4 fogC;",
            "uniform float3 accent;",
            "const float3 LUMA = float3(0.3, 0.59, 0.11);",
            "float bell(float u, float m, float s) { return exp(-(u - m) * (u - m) / (2.0 * s * s)); }",
            "float3 metal(float3 c, float4 lk) {",
            "  float l = dot(c, LUMA);",
            "  float u = (l - lk.x) / lk.y;",
            "  float bump = 1.15 * bell(u, 0.66, 0.1) - 0.4 * bell(u, 0.32, 0.16) + 0.35 * bell(u, 0.98, 0.08);",
            "  float l2 = clamp(l + 0.5 * lk.y * bump, 0.01, 1.0);",
            "  if (lk.z > 0.0 && l2 > l) { l2 = max(l, min(l2, lk.z)); }",
            "  float3 v = c * (l2 / max(l, 0.01));",
            "  float over = max(v.r, max(v.g, v.b)) - 1.0;",
            "  if (over > 0.0) { v = min(v, float3(1.0)); v += (float3(1.0) - v) * min(1.0, over); }",
            "  return v;",
            "}",
            "float2 at(float3 ax, float3 ay, float2 p) { float3 q = float3(p, 1.0); return float2(dot(ax, q), dot(ay, q)); }",
            "float3 flowDeep(float2 p) {",
            "  float3 c = src.eval(at(ax0, ay0, p)).rgb;",
            "  c = mix(c, src.eval(at(ax1, ay1, p)).rgb, 0.72);",
            "  return mix(c, src.eval(at(ax2, ay2, p)).rgb, 0.6);",
            "}",
            "float3 flowLight(float2 p) {",
            "  float3 c = src2.eval(at(ax0, ay0, p) * scale2).rgb;",
            "  c = mix(c, src2.eval(at(ax1, ay1, p) * scale2).rgb, 0.72);",
            "  return mix(c, src2.eval(at(ax2, ay2, p) * scale2).rgb, 0.6);",
            "}",
            "float hash(float2 p) {",
            "  float3 q = fract(float3(p.x, p.y, p.x) * 0.1031);",
            "  q += dot(q, q.yzx + 33.33);",
            "  return fract((q.x + q.y) * q.z);",
            "}",
            "float vnoise(float2 p) {",
            "  float2 i = floor(p); float2 f = fract(p); float2 u = f * f * (3.0 - 2.0 * f);",
            "  float a = hash(i); float b = hash(i + float2(1.0, 0.0)); float c = hash(i + float2(0.0, 1.0)); float d = hash(i + float2(1.0, 1.0));",
            "  return (a + (b - a) * u.x + (c - a) * u.y + (a - b - c + d) * u.x * u.y) * 2.0 - 1.0;",
            "}",
            "float fbm(float2 p, float t) {",
            "  return 0.55 * vnoise(p + float2(t * 0.035, -t * 0.02))",
            "       + 0.3 * vnoise(p * float2(2.1, 2.3) + float2(17.0 - t * 0.05, t * 0.03))",
            "       + 0.15 * vnoise(p * float2(4.3, 4.1) + float2(41.0 + t * 0.07, -t * 0.04));",
            "}",
            "float3 grainOn(float3 c, float2 p) {",
            "  float n = (grain.eval(p).r * 255.0 - 128.0) / 127.0;",
            "  float f = clamp((p.y - grainY.x) / max(1.0, grainY.y - grainY.x), 0.0, 1.0);",
            "  float2 w = mix(grainW.xy, grainW.zw, f);",
            "  return n > 0.0 ? c + (float3(1.0) - c) * (n * w.x) : c * (1.0 + n * w.y);",
            "}",
            "half4 main(float2 p) {",
            "  if (head < 0.5) {",
            "    float3 c = mix(metal(flowDeep(p), look), veil.rgb, veil.a);",
            "    return half4(half3(grainOn(c, p)), 1.0);",
            "  }",
            "  float density = fogA.x; float footDp = fogA.y; float width = fogA.z; float t = fogA.w;",
            "  float2 q = p / density;",
            "  float yy = q.y - footDp;",
            "  float top = clamp(q.y / max(1.0, footDp - 40.0), 0.0, 1.0);",
            "  float3 ink = float3(8.0, 8.0, 12.0) / 255.0;",
            "  float3 vc = ink; float va = 0.0;",
            "  if (fogC.y > 0.5 && fogC.z < 0.5) {",
            "    if (yy < -40.0) { va = 0.55 - 0.05 * top; }",
            "    else if (yy < 30.0) { va = 0.5 * (1.0 - (yy + 40.0) / 70.0); }",
            "    else { vc = accent; va = yy < 140.0 ? 0.3 * (yy - 30.0) / 110.0 : 0.3 + 0.12 * clamp((yy - 140.0) / 220.0, 0.0, 1.0); }",
            "  } else if (fogC.y > 0.5) {",
            "    va = yy < -40.0 ? 0.55 - 0.05 * top : 0.5;",
            "  } else {",
            "    vc = float3(1.0);",
            "    va = yy < 0.0 ? 0.62 - 0.07 * clamp(q.y / footDp, 0.0, 1.0) : 0.55 - 0.43 * clamp(yy / 360.0, 0.0, 1.0);",
            "  }",
            "  float fade = yy < 0.0 ? 1.0 : 1.0 - 0.45 * clamp(yy / 360.0, 0.0, 1.0);",
            "  float2 nq = float2(q.x / 120.0 + fogC.x, yy / 70.0);",
            "  float n = fbm(nq, t); float nn = fbm(nq * float2(1.7, 1.5) + float2(3.1, 7.0), t * 1.3);",
            "  float u = p.x / width; float lean = max(fogB.x > 0.0 ? u : 1.0 - u, 0.0001);",
            "  float reach = (60.0 + 50.0 * fogB.y) * pow(lean, 1.6) + 16.0 * sin(6.2832 * (0.8 * u + fogB.z) + t / 9.0)",
            "      + 9.0 * sin(6.2832 * (2.2 * u + fogB.w) - t / 6.5);",
            "  float am = (1.0 - smoothstep(-60.0, 70.0, yy - 150.0 - reach - 70.0 * n)) * fade;",
            "  float ad = 1.0 - smoothstep(-40.0, 45.0, yy - 20.0 - 0.55 * reach - 40.0 * (0.7 * n + 0.3 * nn));",
            "  float3 d = metal(flowDeep(p), look) * (1.0 - va) + vc * va;",
            "  float ma = fogC.z > 0.5 ? 0.7 : 0.36;",
            "  float3 mv = fogC.z > 0.5 ? float3(29.0, 30.0, 38.0) / 255.0 : float3(1.0);",
            "  float3 m = metal(flowLight(p), look2) * (1.0 - ma) + mv * ma;",
            "  float a = ad + am * (1.0 - ad);",
            "  float3 c = a > 0.001 ? (d * ad + m * am * (1.0 - ad)) / a : m;",
            "  c = grainOn(c, p);",
            "  return half4(half3(c * a), half(a));",
            "}");

    /** 卡片边上一圈金属倒角：上沿一道细亮线，下沿一道细暗线。 */
    private void drawRim(Canvas canvas, int w, int h) {
        if (h != rimH) {
            rimH = h;
            float edge = Math.min(.5f, 3 * density / h);
            rim.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x38FFFFFF, 0x00FFFFFF, 0x00000000, 0x29000000},
                    new float[]{0, edge, 1 - edge, 1}, Shader.TileMode.CLAMP));
        }
        canvas.drawPath(clip, rim);
    }

    private float cell() {
        return mode == MIST ? MIST_CELL_DP : CELL_DP;
    }

    /** 按大小建小位图和缓冲；大小变了返回 true。 */
    private boolean size(int w, int h) {
        float cell = cell() * density;
        int nw = Math.max(2, (int) Math.ceil(w / cell)), nh = Math.max(2, (int) Math.ceil(h / cell));
        if (nw == sw && nh == sh && out != null) return false;
        sw = nw;
        sh = nh;
        work = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        workCanvas = new Canvas(work);
        out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        mask = mode == HEAD ? Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888) : null;
        px = new int[sw * sh];
        px2 = mode == HEAD ? new int[sw * sh] : null;
        res = new int[sw * sh];
        resMask = mode == HEAD ? new int[sw * sh] : null;
        acc = null;
        tmp = new float[sw * sh * 4];
        return true;
    }

    /** 这一帧：三层流动、过金属曲线（详情页头部再合成两层雾），和上一帧平滑，模糊一下，放进 out。 */
    private void compute(long t, long now, boolean moving) {
        Bitmap src = deep != null ? deep : fallback;
        if (src == null) return;
        Look a = look(src, mode == MIST ? (night ? 2 : 0) : dark ? (night ? 2 : 1) : 0);
        flow(a, src, t, px);
        boolean fresh = acc == null || !moving || now - computedAt > frameMs * 3 + 100; // 隔了一阵才画的（刚滚进来）直接用新的
        if (acc == null) acc = new float[sw * sh * 4];
        float blend = fresh ? 1 : fadeFrom > 0 && now - fadeFrom < FADE_MS ? .12f : .35f;
        if (mode == HEAD) {
            if (px2 == null) px2 = new int[sw * sh];
            Bitmap ls = light != null ? light : src;
            flow(look(ls, night ? 2 : 0), ls, t, px2);
            fog(a, look(ls, night ? 2 : 0), t / 1000f, blend);
        } else {
            float[] lut = a.lut;
            for (int i = 0, n = sw * sh; i < n; i++) {
                int c = px[i], o = i * 4;
                metal(c, lut, tmp, o);
                acc[o] += (tmp[o] - acc[o]) * blend;
                acc[o + 1] += (tmp[o + 1] - acc[o + 1]) * blend;
                acc[o + 2] += (tmp[o + 2] - acc[o + 2]) * blend;
                acc[o + 3] = 255;
            }
        }
        soften();
        out.setPixels(res, 0, sw, 0, 0, sw, sh);
        if (mask != null) mask.setPixels(resMask, 0, sw, 0, 0, sw, sh);
    }

    /**
     * 三层模糊小图画到 work 上：每层是边长为对角线 1.4 倍的正方形，四周按边上的颜色往外延。详情页头部按一张卡片的比例
     * （宽的 0.72 倍高）摆在顶上，而不是按整个头部：头部比卡片高得多，按整个算会把图拉大、只露出窄窄一条，
     * 颜色和点开它的那张卡片对不上。
     */
    private void flow(Look a, Bitmap src, long t, int[] into) {
        workCanvas.drawColor(a.base, PorterDuff.Mode.SRC);
        int bh = mode == HEAD ? Math.min(sh, Math.round(sw * .72f)) : sh;
        float side = (float) Math.hypot(sw, bh) * 1.4f, left = (sw - side) / 2, top = (bh - side) / 2;
        for (float[] l : LAYERS) {
            float e = ease(t, (long) (l[0] * 1000));
            float px = left + side * l[2], py = top + side * l[3], s = l[4] + (l[5] - l[4]) * e;
            matrix.reset();
            matrix.setScale(side / src.getWidth(), side / src.getHeight());
            matrix.postTranslate(left + l[7] * side * e, top + l[8] * side * e);
            matrix.postScale(s, s, px, py);
            matrix.postRotate(l[6] * e, px, py);
            a.shader.setLocalMatrix(matrix);
            layer.setShader(a.shader);
            layer.setAlpha(Math.round(255 * l[1]));
            workCanvas.drawRect(0, 0, sw, sh, layer);
        }
        work.getPixels(into, 0, sw, 0, 0, sw, sh);
    }

    /**
     * 金属曲线：按亮度查表（亮度用小数、在表里插值，不取整，曲线不会把 8 位颜色的台阶放大），按比例提亮或压暗，
     * 色相和饱和度不变；提亮溢出的部分才往白里走，像金属最亮的那一点高光。结果放进 to[o..o+2]。
     */
    private static void metal(int c, float[] lut, float[] to, int o) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        float x = (.3f * r + .59f * g + .11f * b) * (1024f / 255);
        int j = Math.min(1023, (int) x);
        float k = lut[j] + (lut[j + 1] - lut[j]) * (x - j);
        float R = r * k, G = g * k, B = b * k, over = Math.max(R, Math.max(G, B)) - 255;
        if (over > 0) {
            float w = Math.min(1, over / 255);
            R = Math.min(255, R);
            G = Math.min(255, G);
            B = Math.min(255, B);
            R += (255 - R) * w;
            G += (255 - G) * w;
            B += (255 - B) * w;
        }
        to[o] = R;
        to[o + 1] = G;
        to[o + 2] = B;
    }

    /**
     * 详情页的雾：某一格有多浓，看它在头部底边下面多深，再减去一条横向起伏的“淌到多深”（颜色重的一侧整体更深，叠两种
     * 长短不同的波，各自慢慢走）和一张流动的噪声（三个倍频，横向拉长，各往不同方向漂）。深的一层收得早、边窄一些，
     * 浅雾淌得远、边很宽；两层用同一张噪声，深色是在浅雾里化开的。噪声隔一格算一次，中间插值。
     */
    private void fog(Look deepLook, Look lightLook, float ts, float blend) {
        float footDp = (getHeight() - tail) / density;
        int gw = sw / 2 + 2, gh = sh / 2 + 2;
        if (n1 == null || n1.length != gw * gh) {
            n1 = new float[gw * gh];
            n2 = new float[gw * gh];
        }
        for (int gy = 0; gy < gh; gy++) {
            float y = ((gy * 2 + .5f) * CELL_DP - footDp) / 70;
            for (int gx = 0; gx < gw; gx++) {
                float x = (gx * 2 + .5f) * CELL_DP / 120 + fogOff;
                n1[gy * gw + gx] = fbm(x, y, ts);
                n2[gy * gw + gx] = fbm(x * 1.7f + 3.1f, y * 1.5f + 7, ts * 1.3f);
            }
        }
        float[] reach = new float[sw];
        for (int x = 0; x < sw; x++) {
            float u = (x + .5f) / sw, lean = side > 0 ? u : 1 - u;
            reach[x] = (60 + 50 * k) * (float) Math.pow(lean, 1.6) + 16 * (float) Math.sin(6.2832 * (.8 * u + r0) + ts / 9)
                    + 9 * (float) Math.sin(6.2832 * (2.2 * u + r1) - ts / 6.5);
        }
        float ma = night ? .7f : .36f;
        int mv = night ? 0xFF1D1E26 : Color.WHITE, ink = 0xFF08080C;
        float[] d = new float[3], m = new float[3];
        for (int y = 0; y < sh; y++) {
            float yDp = (y + .5f) * CELL_DP, yy = yDp - footDp, top = clamp(yDp / Math.max(1, footDp - 40));
            int vc;
            float va;
            if (dark && !night) { // 标题底下压暗；往下淌的那段换成同色的浅色，深色块化开时是透亮的同色而不是灰
                if (yy < -40) {
                    vc = ink;
                    va = .55f - .05f * top;
                } else if (yy < 30) {
                    vc = ink;
                    va = .5f * (1 - (yy + 40) / 70);
                } else {
                    vc = accent;
                    va = yy < 140 ? .3f * (yy - 30) / 110 : .3f + .12f * clamp((yy - 140) / 220);
                }
            } else if (dark) { // 深色模式：往下一直压暗，浅色字底下不会发亮
                vc = ink;
                va = yy < -40 ? .55f - .05f * top : .5f;
            } else {
                vc = Color.WHITE;
                va = yy < 0 ? .62f - .07f * clamp(yDp / footDp) : .55f - .43f * clamp(yy / 360);
            }
            float fade = yy < 0 ? 1 : 1 - .45f * clamp(yy / 360);
            float gyf = y / 2f;
            int gy0 = Math.min(gh - 2, (int) gyf);
            float fy = gyf - gy0;
            for (int x = 0; x < sw; x++) {
                float gxf = x / 2f;
                int gx0 = Math.min(gw - 2, (int) gxf), g00 = gy0 * gw + gx0;
                float fx = gxf - gx0;
                float n = lerp(lerp(n1[g00], n1[g00 + 1], fx), lerp(n1[g00 + gw], n1[g00 + gw + 1], fx), fy);
                float nn = lerp(lerp(n2[g00], n2[g00 + 1], fx), lerp(n2[g00 + gw], n2[g00 + gw + 1], fx), fy);
                float am = (1 - smooth(-60, 70, yy - 150 - reach[x] - 70 * n)) * fade;
                float ad = 1 - smooth(-40, 45, yy - 20 - .55f * reach[x] - 40 * (.7f * n + .3f * nn));
                int i = y * sw + x, o = i * 4;
                metal(px[i], deepLook.lut, d, 0);
                metal(px2[i], lightLook.lut, m, 0);
                float a = ad + am * (1 - ad);
                for (int ch = 0; ch < 3; ch++) {
                    int shift = 16 - ch * 8;
                    float mc = m[ch] * (1 - ma) + ((mv >> shift) & 0xFF) * ma;
                    float dc = d[ch] * (1 - va) + ((vc >> shift) & 0xFF) * va;
                    float v = a > .001f ? (dc * ad + mc * am * (1 - ad)) / a : mc; // 雾外面的格子也给浅雾的颜色，模糊时边上不发黑
                    acc[o + ch] += (v - acc[o + ch]) * blend;
                }
                acc[o + 3] += (a * 255 - acc[o + 3]) * blend;
            }
        }
    }

    /** 和上一帧平滑过的结果再横竖各模糊一次（1-2-1），放大以后亮脊是圆润的，没有折痕；颜色不透明地打包进 res，雾的浓淡进 resMask。 */
    private void soften() {
        int w = sw, h = sh;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int l = Math.max(0, x - 1), r = Math.min(w - 1, x + 1), o = (y * w + x) * 4;
                for (int c = 0; c < 4; c++) {
                    tmp[o + c] = (acc[(y * w + l) * 4 + c] + 2 * acc[o + c] + acc[(y * w + r) * 4 + c]) / 4;
                }
            }
        }
        for (int y = 0; y < h; y++) {
            int u = Math.max(0, y - 1), d = Math.min(h - 1, y + 1);
            for (int x = 0; x < w; x++) {
                int o = (y * w + x) * 4, ou = (u * w + x) * 4, od = (d * w + x) * 4;
                int a = Math.round((tmp[ou + 3] + 2 * tmp[o + 3] + tmp[od + 3]) / 4);
                int r = Math.round((tmp[ou] + 2 * tmp[o] + tmp[od]) / 4);
                int g = Math.round((tmp[ou + 1] + 2 * tmp[o + 1] + tmp[od + 1]) / 4);
                int b = Math.round((tmp[ou + 2] + 2 * tmp[o + 2] + tmp[od + 2]) / 4);
                res[y * w + x] = 0xFF000000 | clamp8(r) << 16 | clamp8(g) << 8 | clamp8(b);
                if (resMask != null) resMask[y * w + x] = clamp8(a) << 24;
            }
        }
    }

    /** 一张图的“金属曲线”：亮度先按这张图自己的亮度范围（5%～95% 分位）归一，同一条曲线在深的浅的图上都起在同样的位置。 */
    private static final class Look {
        final int base;
        final float lo, span, cap; // 这张图的亮度范围（5%～95% 分位）；亮脊封顶到多亮，0 是不封顶
        final float[] lut = new float[1025];
        final BitmapShader shader;

        /** cap：0 不封顶；1 是白字的底，亮脊最多提到 0.58；2 是深色模式的白字底，0.46。 */
        Look(Bitmap b, int cap) {
            int w = b.getWidth(), h = b.getHeight(), all = w * h, step = Math.max(1, all / 6000), n = (all + step - 1) / step;
            int[] p = new int[all];
            b.getPixels(p, 0, w, 0, 0, w, h);
            float[] ls = new float[n];
            long rs = 0, gs = 0, bs = 0;
            for (int i = 0; i < n; i++) { // 大图隔几个点取一个，分位数和平均色一样准
                int c = p[i * step];
                ls[i] = (.3f * Color.red(c) + .59f * Color.green(c) + .11f * Color.blue(c)) / 255;
                rs += Color.red(c);
                gs += Color.green(c);
                bs += Color.blue(c);
            }
            base = Color.rgb((int) (rs / n), (int) (gs / n), (int) (bs / n));
            Arrays.sort(ls);
            lo = ls[(int) (n * .05f)];
            span = Math.max(.12f, ls[Math.min(n - 1, (int) (n * .95f))] - lo);
            this.cap = cap == 2 ? .46f : cap == 1 ? .58f : 0;
            for (int i = 0; i <= 1024; i++) {
                float l = i / 1024f, u = (l - lo) / span;
                float bump = 1.15f * bell(u, .66f, .1f) - .4f * bell(u, .32f, .16f) + .35f * bell(u, .98f, .08f);
                float l2 = Math.max(.01f, Math.min(1, l + .5f * span * bump));
                if (cap > 0 && l2 > l) l2 = Math.max(l, Math.min(l2, cap == 2 ? .46f : .58f));
                lut[i] = l2 / Math.max(l, .01f);
            }
            shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
    }

    private static Look look(Bitmap b, int cap) {
        Look[] ls = LOOKS.get(b);
        if (ls == null) LOOKS.put(b, ls = new Look[3]);
        if (ls[cap] == null) ls[cap] = new Look(b, cap);
        return ls[cap];
    }

    private float[] radii() {
        if (corners == null) return null;
        Drawable bg = corners.getBackground();
        if (bg instanceof RippleDrawable) bg = ((RippleDrawable) bg).getDrawable(0);
        return bg instanceof GradientDrawable ? Theme.radii((GradientDrawable) bg) : null;
    }

    private static float bell(float u, float m, float s) {
        return (float) Math.exp(-(u - m) * (u - m) / (2 * s * s));
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    private static int clamp8(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }

    private static float smooth(float a, float b, float x) {
        float u = clamp((x - a) / (b - a));
        return u * u * (3 - 2 * u);
    }

    private static float hash(int i, int j) {
        int h = i * 374761393 + j * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFFFFL) / 4294967296f;
    }

    /** 值噪声：格点上随机，中间平滑插值，-1 到 1。 */
    private static float vnoise(float x, float y) {
        int i = (int) Math.floor(x), j = (int) Math.floor(y);
        float fx = x - i, fy = y - j, u = fx * fx * (3 - 2 * fx), v = fy * fy * (3 - 2 * fy);
        float a = hash(i, j), b = hash(i + 1, j), c = hash(i, j + 1), d = hash(i + 1, j + 1);
        return (a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v) * 2 - 1;
    }

    /** 三个倍频，各往不同方向漂。 */
    private static float fbm(float x, float y, float t) {
        return .55f * vnoise(x + t * .035f, y - t * .02f) + .3f * vnoise(x * 2.1f - t * .05f + 17, y * 2.3f + t * .03f)
                + .15f * vnoise(x * 4.3f + t * .07f + 41, y * 4.1f - t * .04f);
    }

    /** 来回往复、缓入缓出：0 到 1 再回到 0，一个来回 2 × period。 */
    private static float ease(long t, long period) {
        double phase = (double) t / period, frac = phase - Math.floor(phase);
        double x = ((long) Math.floor(phase)) % 2 == 0 ? frac : 1 - frac;
        return (float) ((1 - Math.cos(Math.PI * x)) / 2);
    }
}
