package cloud.boxpkaka.infofeed;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ColorSpace;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.Half;
import android.util.LruCache;
import android.view.View;
import android.view.ViewTreeObserver;

import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 雾面金属（见 docs/design.md）：像 Apple Music 播放页那样流动的颜色，过一条“金属曲线”，表面是干净的，不加颗粒。
 * 一张图（模糊过的封面，或者按话题生成的抽象封面，都是 192×108、16 位浮点，见 art）画三遍，各自绕不同的中心慢慢转、缩放、
 * 漂移（来回往复，缓入缓出，24～42 秒一个来回）；每个点按亮度过一条“金属曲线”：在这张图自己的亮度范围里，66% 那一档起一道
 * 窄的亮脊，32% 那一档压暗一点，颜色流动时亮脊沿着等高线滑动、变形，像流动的金属，色相不变。图的四周按边上的颜色往外延
 * （CLAMP），转到什么角度都不露直边。再盖遮罩。
 * 没有封面的（抽象封面：专题卡片和标题栏、没封面的“最值得看”和它的详情页头部、报告的要点）用哑光的曲线：不起窄亮脊、
 * 不冲白，暗部压深，亮部只有一层很宽很柔的光泽。抽象封面是几团圆形渐变，亮脊会在每团外面起一圈规则的亮环，像不锈钢。
 * 小图放大 8 倍左右，金属曲线又在亮脊附近把明暗差放大三四倍，所以底图用 16 位浮点（8 位的一档台阶会被放大成一圈色带），
 * GPU 上按三次 B 样条取样（双线性在每个像素格的边上会折一下，放大后看得出），最后加 ±0.5 档的抖动消掉剩下的色带，看不出来。
 * 安卓 13 起整个在 GPU 上逐点算（Gpu，RuntimeShader），没有格子、没有放大的痕迹，也不占主线程：卡片按屏幕像素算，
 * 铺满整屏又很柔的页面的雾和详情页的颜色在缩小的图层上算、平滑放大（见 drawGpu）；
 * 老系统在 4dp 一格的小位图上算（compute），亮度用小数、和上一帧平滑，放大画上去；新系统上软件画布（卡片展开前拍的截图）
 * 也走这一路，算法相同，颜色接得上。
 * 三种用法：
 * - 卡片（首页“最值得看”、专题卡片、专题标题栏、报告的“要点”）：盖一层遮罩（字压在上面也看得清），边上一圈细倒角，
 *   圆角跟着卡片的背景（右滑时背景的圆角会变）；
 * - 详情页头部（bleed）：铺满整页、不跟着滚动，往下淌成一团雾。深的一层在标题下面化开，浅的一层（同一张图的亮色版本）
 *   淌进正文，上面再飘一层水汽；雾的边由“淌到多深”的起伏和一张翻涌的噪声决定，一团团、一缕缕，没有固定的轮廓。
 *   正文滚动、拉动时各层按不同的比例跟着走（parallax），字是浮在上面的；
 * - 页面的雾（mist）：每一页最底下，同一页颜色的亮色版本盖一层白（深色模式压暗），流得是卡片的三分之一快。
 * 动画按全局时钟加每件事的错开量算，同一件事的卡片和详情页走在同一个位置，展开时接得上；系统关了动画时静止。
 * 圆角靠 clipPath 而不是轮廓裁剪，Card 用 View.draw 截图时也是圆角。
 */
final class Flow extends View {
    /**
     * 三层：时长（秒）、透明度、转动中心（占这一层的比例）、缩放起止、转角、平移（占这一层的比例）。柔和、缓慢：一个来回
     * 24～42 秒，幅度不大，亮脊是慢慢滑过去的，不是飞过去的；幅度也不能太小，否则会长时间停在图的一角，颜色轮换不起来。
     */
    private static final float[][] LAYERS = {
            {24, 1f, .5f, .5f, 1f, 1.22f, 105, .1f, -.07f},
            {33, .72f, .24f, .32f, 1.14f, 1.32f, -133, -.11f, .085f},
            {42, .6f, .76f, .68f, 1.24f, 1.06f, 154, .105f, .1f},
    };
    private static final int CARD = 0, HEAD = 1, MIST = 2;
    private static final float CELL_DP = 4, MIST_CELL_DP = 8; // 小位图一格多少 dp：页面的雾很软，格子大一倍
    private static final long FRAME_MS = 33, MIST_FRAME_MS = 66, FADE_MS = 400;
    private static final LruCache<String, Bitmap> BLURRED = new LruCache<>(64), ARTS = new LruCache<>(48);
    private static final LruCache<Bitmap, Bitmap> LIFTED = new LruCache<>(32);
    private static final LruCache<Bitmap, Look[]> LOOKS = new LruCache<>(96);
    private static final boolean GPU = Build.VERSION.SDK_INT >= 33;
    private static final long START = SystemClock.uptimeMillis(); // GPU 上雾的噪声按进程启动以来的秒数算，数小，精度够
    private static final Set<Activity> HELD = Collections.newSetFromMap(new WeakHashMap<>()); // 被上面一页整个盖住的页面，见 hold
    private static final Set<Flow> PARKED = Collections.newSetFromMap(new WeakHashMap<>()); // 因此停下没再排下一帧的

    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG), layer = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint veil = new Paint(), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dstIn = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final Rect visible = new Rect(), dst = new Rect();
    private final Path clip = new Path();
    private final float density;
    private final boolean night;
    private int mode = CARD;
    private boolean dark, dirty = true, matte; // matte：哑光（抽象封面），见类注释
    private int accent;
    private long offset, fadeFrom;
    private float slow = 1;
    private Bitmap deep, light, fallback; // 颜色来源：深的一层；详情页往下淌的浅雾；来源下来前的两色渐变
    private View corners;
    // 详情页的雾：颜色重的一侧（1 右、-1 左）、深浅差、两种起伏的相位、噪声的错开量
    private float side = 1, k, r0, r1, fogOff;
    // 分层（见 parallax）：深色化开的边、浅雾、水汽的基准线（视图里的像素），流动的颜色整体往上走了多少
    private float foot, fogFoot, vaporFoot, shift;
    // 小位图：sw × sh 格。work 上画三层，算好的结果平滑以后放进 out，放大画上去
    private int sw, sh;
    private Bitmap work, out, mask; // mask：详情页头部的雾有多浓（只用透明度），out 是不透明的颜色
    private Canvas workCanvas;
    private int[] px, px2, res, resMask;
    private float[] acc, tmp, n1, n2, n3, n4;
    private Gpu shade, before; // GPU：现在的这一套；换了来源、正在淡入时，之前的那一套垫在下面
    private RenderNode low; // 页面的雾和详情页的颜色先画在这块缩小的画布上，再放大（见 drawGpu）
    private final float[] values = new float[9];
    private long computedAt = -1, frameMs = FRAME_MS; // 多久重算一次：按上一次算了多久定，主线程最多花四分之一在这上面
    private int rimH = -1;

    Flow(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        dstIn.setBlendMode(BlendMode.DST_IN);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2 * density); // 一半在形状外面被裁掉，露出 1dp
    }

    /** 做过的模糊小图，没做过返回 null。 */
    static Bitmap cachedBlur(String key) {
        return BLURRED.get(key);
    }

    /**
     * 封面缩成 192×108、横竖各模糊三遍（半径 12，三遍盒子模糊接近高斯）的图；同一张只做一次。模糊按小数算、存成 16 位浮点，
     * 不取整（见类注释）。图比要铺的地方小，但已经很模糊了，放大不会有棱角。
     */
    static Bitmap blurred(String key, Bitmap cover) {
        Bitmap b = BLURRED.get(key);
        if (b != null) return b;
        int w = 192, h = 108;
        int[] px = new int[w * h];
        Bitmap.createScaledBitmap(cover, w, h, true).getPixels(px, 0, w, 0, 0, w, h);
        float[] a = new float[w * h * 3], tmp = new float[w * h * 3];
        for (int i = 0; i < w * h; i++) {
            a[i * 3] = ((px[i] >> 16) & 0xFF) / 255f;
            a[i * 3 + 1] = ((px[i] >> 8) & 0xFF) / 255f;
            a[i * 3 + 2] = (px[i] & 0xFF) / 255f;
        }
        for (int pass = 0; pass < 3; pass++) {
            box(a, tmp, w, h, 12, true);
            box(tmp, a, w, h, 12, false);
        }
        b = f16(a, w, h);
        BLURRED.put(key, b);
        return b;
    }

    /** 盒子模糊一遍（横或竖）：滑动窗口累加，边上按最边上的颜色往外延。in、out 是每点三个小数（RGB）。 */
    private static void box(float[] in, float[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        float n = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            float rs = 0, gs = 0, bs = 0;
            for (int k = -r; k <= r; k++) {
                int c = at(line, Math.max(0, Math.min(len - 1, k)), w, horizontal);
                rs += in[c];
                gs += in[c + 1];
                bs += in[c + 2];
            }
            for (int i = 0; i < len; i++) {
                int o = at(line, i, w, horizontal);
                out[o] = rs / n;
                out[o + 1] = gs / n;
                out[o + 2] = bs / n;
                int add = at(line, Math.min(len - 1, i + r + 1), w, horizontal), drop = at(line, Math.max(0, i - r), w, horizontal);
                rs += in[add] - in[drop];
                gs += in[add + 1] - in[drop + 1];
                bs += in[add + 2] - in[drop + 2];
            }
        }
    }

    private static int at(int line, int i, int w, boolean horizontal) {
        return (horizontal ? line * w + i : i * w + line) * 3;
    }

    /** 一张空的 16 位浮点图（sRGB，不透明的颜色照常画上去）。 */
    private static Bitmap f16(int w, int h) {
        return Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_F16, true, ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB));
    }

    /** 把每点三个小数（RGB，0 到 1）存成 16 位浮点图。 */
    private static Bitmap f16(float[] rgb, int w, int h) {
        Bitmap b = f16(w, h);
        short[] half = new short[w * h * 4];
        short one = Half.toHalf(1f);
        for (int i = 0; i < w * h; i++) {
            half[i * 4] = Half.toHalf(rgb[i * 3]);
            half[i * 4 + 1] = Half.toHalf(rgb[i * 3 + 1]);
            half[i * 4 + 2] = Half.toHalf(rgb[i * 3 + 2]);
            half[i * 4 + 3] = one;
        }
        b.copyPixelsFromBuffer(ShortBuffer.wrap(half));
        return b;
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
        b = f16(blurred.getWidth(), blurred.getHeight());
        Paint p = new Paint();
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        new Canvas(b).drawBitmap(blurred, 0, 0, p);
        LIFTED.put(blurred, b);
        return b;
    }

    /**
     * 没有封面时的“抽象封面”：192×108 的图（都是很柔的渐变，放大也没有棱角；再大的话在主线程上生成太慢），话题色系的六种颜色（见 Theme.artColors）铺成七团径向渐变，位置按 seed 定。
     * 画在 16 位浮点的图上：渐变不取整，也没有 8 位画布上的抖动花纹（放大以后是一格一格的）。
     * 同一件事（seed 是事件 id）的卡片和详情页、同一个专题（seed 是 seedOf）的卡片和专题页是同一张。pastel 是只有亮色的那张。
     */
    static Bitmap art(Theme t, String key, long seed, boolean pastel) {
        String k = key + "|" + seed + "|" + t.night + "|" + pastel;
        Bitmap b = ARTS.get(k);
        if (b != null) return b;
        int w = 192, h = 108;
        b = f16(w, h);
        Canvas c = new Canvas(b);
        int[] cols = t.artColors(key, pastel);
        c.drawColor(cols[0]);
        float[] r = seq(seed, 24);
        float[][] blobs = {{1, .42f}, {2, .36f}, {3, .36f}, {4, .24f}, {5, .22f}, {1, .2f}, {2, .18f}};
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        matte = false;
        fallback = f16(1, 2);
        fallback.setPixels(new int[]{top, bottom}, 0, 1, 0, 0, 1, 2);
        refresh(false);
    }

    /** 换成流动的模糊封面：fade 为 true 时从现在的颜色慢慢过渡过去（图是刚下来的），否则直接显示。 */
    void cover(Bitmap blurred, boolean fade) {
        if (blurred == deep) return;
        deep = blurred;
        light = night ? blurred : lifted(blurred);
        refresh(fade);
    }

    /** 没有封面：用话题（或主色、珍珠银）生成的抽象封面，seed 见 art；表面是哑光的。 */
    void topic(Theme t, String key, long seed) {
        deep = art(t, key, seed, false);
        light = art(t, key, seed, !night);
        matte = true;
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
     * 详情页头部往下淌（铺满整页、不跟着滚动，边在哪见 parallax）：颜色重的一侧（封面下半张哪边更重，见 lean；接近 0 时
     * 按 id 随机选一侧）淌得更深。accent 是卡片的点缀色：浅色模式下往下淌的那段，深色盖的遮罩换成它，深色块化开时是
     * 透亮的同色而不是灰；水汽也带一点它。
     */
    void bleed(float lean, long id, int accent) {
        float[] r = seq(id, 2);
        if (Math.abs(lean) < .1f) lean = (r[0] < .5f ? -1 : 1) * (.25f + r[1] * .3f);
        side = lean > 0 ? 1 : -1;
        k = Math.min(1, Math.abs(lean) * 2.5f);
        float[] q = seq(id * 7 + 5, 2);
        r0 = q[0];
        r1 = q[1];
        fogOff = id % 7;
        this.accent = accent;
        mode = HEAD;
        refresh(false);
    }

    /**
     * 分层（详情页，见 EventActivity.parallax）：正文滚动或拉动时，后面的各层按不同的比例跟着走。foot、fogFoot、vaporFoot
     * 是深色化开的边、浅雾、水汽的基准线（视图里的像素），shift 是流动的颜色整体往上走了多少；页面的雾只用 shift。
     */
    void parallax(float foot, float fogFoot, float vaporFoot, float shift) {
        if (foot == this.foot && fogFoot == this.fogFoot && vaporFoot == this.vaporFoot && shift == this.shift) return;
        this.foot = foot;
        this.fogFoot = fogFoot;
        this.vaporFoot = vaporFoot;
        this.shift = shift;
        dirty = true;
        invalidate();
    }

    /** 详情页头部的雾和水汽整个滚到屏幕上面去了：不画，也不排下一帧，滚回来时 parallax 叫醒。 */
    private boolean gone() {
        return mode == HEAD && Math.max(foot + 220 * density, Math.max(fogFoot + 640 * density, vaporFoot + 500 * density)) <= 0;
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
        if (w == 0 || h == 0 || gone()) return;
        boolean moving = ValueAnimator.areAnimatorsEnabled();
        long now = SystemClock.uptimeMillis();
        boolean fading = moving && fadeFrom > 0 && now - fadeFrom < FADE_MS;
        if (GPU && canvas.isHardwareAccelerated()) { // 软件画布（Card 给卡片拍截图）不支持 RuntimeShader，那一帧走下面的小位图
            drawGpu(canvas, w, h, (long) ((moving ? now : 0) / slow) + offset, (moving ? now - START : 0) / 1000f + offset / 1000f,
                    fading ? (now - fadeFrom) / (float) FADE_MS : 1);
            if (next(moving || fading)) postInvalidateDelayed(mode == MIST ? FRAME_MS : 16); // 最多六十帧，不跟着高刷屏跑满；页面的雾流得慢，三十帧够了
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
        if (mode == HEAD) { // 不透明的颜色按雾的浓淡淡出
            int layer = canvas.saveLayer(0, 0, w, h, null);
            canvas.drawBitmap(out, null, dst, image);
            canvas.drawBitmap(mask, null, dst, dstIn);
            canvas.restoreToCount(layer);
        } else {
            canvas.drawBitmap(out, null, dst, image);
            veil.setColor(mode == MIST ? (night ? 0xBD101117 : 0x99FFFFFF) : dark ? 0x6B08080C : 0x94FFFFFF);
            canvas.drawRect(0, 0, w, h, veil);
            if (radii != null) drawRim(canvas, w, h);
        }
        canvas.restoreToCount(save);
        if (next(moving || fading)) postInvalidateOnAnimation();
    }

    /**
     * 要不要排下一帧：还在动、看得见、这一页没被盖住。被盖住的记下来，露出来时一起叫醒（hold）；在屏幕外的（从右边推进来的
     * 页面第一帧在屏幕外，滚出去的卡片）等窗口下次画的时候看一眼，回到屏幕上就接着流，不会一直停在那一帧。
     */
    private boolean next(boolean animating) {
        if (!animating || !isShown()) return false;
        if (!getGlobalVisibleRect(visible)) {
            watch(true);
            return false;
        }
        if (held()) {
            PARKED.add(this);
            return false;
        }
        return true;
    }

    private ViewTreeObserver watched;
    private final ViewTreeObserver.OnPreDrawListener peek = () -> {
        if (isShown() && getGlobalVisibleRect(visible)) {
            watch(false);
            invalidate();
        }
        return true;
    };

    private void watch(boolean on) {
        if (on == (watched != null)) return;
        if (on) {
            watched = getViewTreeObserver();
            watched.addOnPreDrawListener(peek);
        } else {
            if (watched.isAlive()) watched.removeOnPreDrawListener(peek);
            watched = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        watch(false);
    }

    /**
     * page 被上面停稳的一页整个盖住了（on 为 true），或者马上要露出来（false）。盖住的时候这一页的流动画完手上这一帧就
     * 不再排下一帧（看不见，白占显卡，也拖慢上面那页）；露出来之前叫醒，下一帧按统一的时钟画出此刻的样子，接得上，
     * 不会从停下的地方跳过去。返回这一页之前是不是停着的（Card、Push 据此决定要不要晚一帧再动，先让它画好）。
     */
    static boolean hold(Activity page, boolean on) {
        if (page == null) return false;
        if (on) return !HELD.add(page);
        if (!HELD.remove(page)) return false;
        for (Iterator<Flow> it = PARKED.iterator(); it.hasNext(); ) {
            Flow f = it.next();
            if (activity(f.getContext()) == page) {
                it.remove();
                f.invalidate();
            }
        }
        return true;
    }

    private boolean held() {
        return !HELD.isEmpty() && HELD.contains(activity(getContext()));
    }

    /** 控件所在的页面。 */
    static Activity activity(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /**
     * GPU 上画一帧：t 是流动的时钟（毫秒），ts 是雾的噪声用的秒数，in 是新来源淡入到哪了（1 是淡完）。
     * 换了来源（图下来了、颜色定了）时新建一套；要淡入的，旧的那套先画在下面，新的按 in 盖上去。
     * 页面的雾和详情页的颜色铺满整屏、又都很柔（没有比几十 dp 更细的东西），按屏幕像素逐点算太费（120Hz 下一帧
     * 只有 8ms）：在三分之一（雾）、二分之一（详情页）大小的画布上算，再平滑放大，看不出差别。卡片小、有亮脊和细倒角，照旧按屏幕像素算。
     */
    private void drawGpu(Canvas screen, int w, int h, long t, float ts, float in) {
        Bitmap src = deep != null ? deep : fallback, src2 = mode == HEAD && light != null ? light : src;
        if (src == null) return;
        if (shade == null || shade.deep != src || shade.light != src2) {
            before = shade != null && fadeFrom > 0 ? shade : null;
            shade = new Gpu(src, src2);
        }
        if (in >= 1) before = null;
        float down = mode == MIST ? 3 : mode == HEAD ? 2 : 1;
        Canvas canvas = screen;
        if (down > 1) {
            if (low == null) {
                low = new RenderNode("flow");
                low.setUseCompositingLayer(true, null); // 画到一块缩小的图层上，放大时按线性插值
            }
            int lw = (int) Math.ceil(w / down), lh = (int) Math.ceil(h / down);
            low.setPosition(0, 0, lw, lh);
            RecordingCanvas rc = low.beginRecording(lw, lh);
            rc.scale(1 / down, 1 / down); // 着色器里的坐标照旧是视图里的像素
            canvas = rc;
        }
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
        if (down > 1) {
            low.endRecording();
            int s = screen.save();
            screen.scale(down, down);
            screen.drawRenderNode(low);
            screen.restoreToCount(s);
        }
    }

    /** 这一帧的参数：三层各自从屏幕像素到图上像素的变换、两张图的金属曲线、遮罩，详情页头部再加雾。 */
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
            matrix.postTranslate(0, -shift); // 分层：颜色整体往上走（见 parallax）
            matrix.invert(matrix); // 反过来：屏幕上的点在图上的哪
            matrix.getValues(v);
            s.setFloatUniform("ax" + i, v[0], v[1], v[2]);
            s.setFloatUniform("ay" + i, v[3], v[4], v[5]);
        }
        s.setFloatUniform("scale2", g.light.getWidth() / (float) g.deep.getWidth(), g.light.getHeight() / (float) g.deep.getHeight());
        Look a = look(g.deep, mode == MIST ? (night ? 2 : 0) : dark ? (night ? 2 : 1) : 0, matte), b = look(g.light, night ? 2 : 0, false);
        s.setFloatUniform("look", a.lo, a.span, a.cap, a.matte ? 1 : 0);
        s.setFloatUniform("look2", b.lo, b.span, b.cap, 0);
        if (mode == HEAD) { // foot：头部的底边，上面是标题底下的颜色，往下是雾
            s.setFloatUniform("head", 1);
            s.setFloatUniform("fogA", density, foot / density, w, ts);
            s.setFloatUniform("fogB", side, k, r0, r1);
            s.setFloatUniform("fogC", fogOff, dark ? 1 : 0, night ? 1 : 0, 0);
            s.setFloatUniform("fogD", fogFoot / density, vaporFoot / density, shift / density, 0);
            s.setFloatUniform("accent", Color.red(accent) / 255f, Color.green(accent) / 255f, Color.blue(accent) / 255f);
            s.setFloatUniform("veil", 0, 0, 0, 0);
        } else {
            int c = mode == MIST ? (night ? 0xBD101117 : 0x99FFFFFF) : dark ? 0x6B08080C : 0x94FFFFFF;
            s.setFloatUniform("veil", Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f, Color.alpha(c) / 255f);
            s.setFloatUniform("head", 0);
            s.setFloatUniform("fogA", density, 0, w, 0);
            s.setFloatUniform("fogB", 0, 0, 0, 0);
            s.setFloatUniform("fogC", 0, 0, 0, 0);
            s.setFloatUniform("fogD", 0, 0, 0, 0);
            s.setFloatUniform("accent", 0, 0, 0);
        }
        g.paint.setShader(s);
    }

    /**
     * GPU 上的一套：一个 RuntimeShader（见 SKSL）和它读的两张图（深的一层、详情页往下淌的浅雾，其余用法两张是同一张）。
     * 图按线性插值取样、四周往外延，着色器里拼成三次 B 样条。
     */
    private static final class Gpu {
        final Bitmap deep, light;
        final RuntimeShader shader = new RuntimeShader(SKSL);
        final Paint paint = new Paint();

        Gpu(Bitmap deep, Bitmap light) {
            this.deep = deep;
            this.light = light;
            shader.setInputShader("src", linear(deep));
            shader.setInputShader("src2", linear(light));
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
            "uniform float3 ax0; uniform float3 ay0; uniform float3 ax1; uniform float3 ay1; uniform float3 ax2; uniform float3 ay2;",
            "uniform float2 scale2;",
            "uniform float4 look;",
            "uniform float4 look2;",
            "uniform float4 veil;",
            "uniform float head;",
            "uniform float4 fogA;",
            "uniform float4 fogB;",
            "uniform float4 fogC;",
            "uniform float4 fogD;",
            "uniform float3 accent;",
            "const float3 LUMA = float3(0.3, 0.59, 0.11);",
            "float bell(float u, float m, float s) { return exp(-(u - m) * (u - m) / (2.0 * s * s)); }",
            "float3 metal(float3 c, float4 lk) {",
            "  float l = dot(c, LUMA);",
            "  float u = (l - lk.x) / lk.y;",
            "  float l2 = lk.w > 0.5 ? l * mix(0.6, 1.0, smoothstep(0.0, 0.65, u)) + 0.175 * lk.y * bell(u, 0.72, 0.24)", // 哑光
            "      : l + 0.5 * lk.y * (1.15 * bell(u, 0.66, 0.1) - 0.4 * bell(u, 0.32, 0.16) + 0.35 * bell(u, 0.98, 0.08));",
            "  l2 = clamp(l2, 0.01, 1.0);",
            "  if (lk.z > 0.0 && l2 > l) { l2 = max(l, min(l2, lk.z)); }",
            "  float3 v = c * (l2 / max(l, 0.01));",
            "  float over = max(v.r, max(v.g, v.b)) - 1.0;",
            "  if (over > 0.0) { v = min(v, float3(1.0)); v += (float3(1.0) - v) * min(1.0, over); }",
            "  return v;",
            "}",
            "float2 at(float3 ax, float3 ay, float2 p) { float3 q = float3(p, 1.0); return float2(dot(ax, q), dot(ay, q)); }",
            // 三次 B 样条取样：四次双线性取样拼成（GPU Gems 2 第 20 章），c 是图上的像素坐标
            "float2 bw0(float2 f) { return (1.0 - f) * (1.0 - f) * (1.0 - f) / 6.0; }",
            "float2 bw1(float2 f) { return (4.0 - 6.0 * f * f + 3.0 * f * f * f) / 6.0; }",
            "float2 bw3(float2 f) { return f * f * f / 6.0; }",
            "void bspline(float2 c, out float2 h0, out float2 h1, out float2 s0, out float2 s1) {",
            "  float2 x = c - 0.5; float2 i = floor(x); float2 f = x - i;",
            "  float2 w0 = bw0(f); float2 w1 = bw1(f); float2 w3 = bw3(f);",
            "  s0 = w0 + w1; s1 = 1.0 - s0;",
            "  h0 = i - 0.5 + w1 / s0; h1 = i + 1.5 + w3 / s1;",
            "}",
            "float3 cubic(float2 c) {",
            "  float2 h0; float2 h1; float2 s0; float2 s1; bspline(c, h0, h1, s0, s1);",
            "  return s0.y * (s0.x * src.eval(h0).rgb + s1.x * src.eval(float2(h1.x, h0.y)).rgb)",
            "       + s1.y * (s0.x * src.eval(float2(h0.x, h1.y)).rgb + s1.x * src.eval(h1).rgb);",
            "}",
            "float3 cubic2(float2 c) {",
            "  float2 h0; float2 h1; float2 s0; float2 s1; bspline(c, h0, h1, s0, s1);",
            "  return s0.y * (s0.x * src2.eval(h0).rgb + s1.x * src2.eval(float2(h1.x, h0.y)).rgb)",
            "       + s1.y * (s0.x * src2.eval(float2(h0.x, h1.y)).rgb + s1.x * src2.eval(h1).rgb);",
            "}",
            "float3 flowDeep(float2 p) {",
            "  float3 c = cubic(at(ax0, ay0, p));",
            "  c = mix(c, cubic(at(ax1, ay1, p)), 0.72);",
            "  return mix(c, cubic(at(ax2, ay2, p)), 0.6);",
            "}",
            "float3 flowLight(float2 p) {",
            "  float3 c = cubic2(at(ax0, ay0, p) * scale2);",
            "  c = mix(c, cubic2(at(ax1, ay1, p) * scale2), 0.72);",
            "  return mix(c, cubic2(at(ax2, ay2, p) * scale2), 0.6);",
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
            // ±0.5 档的抖动（interleaved gradient noise）：消掉 8 位屏幕上剩下的色带，看不出来
            "float dither(float2 p) { return (fract(52.9829189 * fract(dot(floor(p), float2(0.06711056, 0.00583715)))) - 0.5) / 255.0; }",
            "half4 main(float2 p) {",
            "  if (head < 0.5) {",
            "    float3 c = mix(metal(flowDeep(p), look), veil.rgb, veil.a);",
            "    return half4(half3(c + dither(p)), 1.0);",
            "  }",
            "  float density = fogA.x; float footDp = fogA.y; float width = fogA.z; float t = fogA.w;",
            "  float2 q = p / density;",
            "  float yy = q.y - footDp;", // 深色化开的边
            "  float yl = q.y - fogD.x;", // 浅雾
            "  float yv = q.y - fogD.y;", // 水汽
            "  if (yy > 220.0 && yl > 640.0 && yv > 500.0) { return half4(0.0); }",
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
            "  float fade = yl < 0.0 ? 1.0 : 1.0 - 0.45 * clamp(yl / 360.0, 0.0, 1.0);",
            // 雾边的噪声：先用一层很平滑的慢噪声把坐标扭一扭，边是一团团往外翻涌的，不是一张花纹平移过去；扭得轻，不会挤出陡边
            "  float tf = t * 2.6;",
            "  float2 nq = float2(q.x / 120.0 + fogC.x, yl / 70.0);",
            "  float2 warp = float2(vnoise(nq * 0.6 + float2(1.7 + tf * 0.03, 9.2)), vnoise(nq * 0.6 + float2(8.3, 2.8 - tf * 0.03)));",
            "  float n = fbm(nq + 0.4 * warp, tf); float nn = fbm(nq * float2(1.7, 1.5) + float2(3.1, 7.0) + 0.3 * warp, tf * 1.3);",
            "  float u = p.x / width; float lean = max(fogB.x > 0.0 ? u : 1.0 - u, 0.0001);",
            "  float reach = (60.0 + 50.0 * fogB.y) * pow(lean, 1.6) + 20.0 * sin(6.2832 * (0.8 * u + fogB.z) + t / 3.2)",
            "      + 12.0 * sin(6.2832 * (2.2 * u + fogB.w) - t / 2.4);",
            "  float am = (1.0 - smoothstep(-60.0, 70.0, yl - 150.0 - reach - 70.0 * n)) * fade;",
            "  float ad = 1.0 - smoothstep(-40.0, 45.0, yy - 20.0 - 0.55 * reach - 40.0 * (0.7 * n + 0.3 * nn));",
            // 深色上飘过的雾：大块、很软的明暗慢慢漂移，整片颜色在呼吸（跟着流动的颜色走，见 fogD.z）
            "  if (yy < 60.0) {",
            "    float cloud = fbm(float2(q.x / 220.0 + fogC.x * 0.7, (q.y + fogD.z) / 160.0), tf * 0.6);",
            "    va = clamp(va - 0.1 * cloud * (1.0 - smoothstep(0.0, 60.0, yy)), 0.0, 1.0);",
            "  }",
            "  float3 d = ad > 0.001 ? metal(flowDeep(p), look) * (1.0 - va) + vc * va : float3(0.0);",
            "  float ma = fogC.z > 0.5 ? 0.7 : 0.36;",
            "  float3 mv = fogC.z > 0.5 ? float3(29.0, 30.0, 38.0) / 255.0 : float3(1.0);",
            "  float3 m = ad < 0.999 ? metal(flowLight(p), look2) * (1.0 - ma) + mv * ma : mv;",
            "  float a = ad + am * (1.0 - ad);",
            "  float3 c = a > 0.001 ? (d * ad + m * am * (1.0 - ad)) / a : m;",
            // 水汽：浅色的一片，边很散，从深色化开的地方往上飘、横着漂，盖在雾上面，自己一层
            "  float vv = 0.0;",
            "  if (yv > -60.0 && yv < 500.0) {",
            "    float2 vq = float2(q.x / 150.0 + fogC.x * 1.3 - t * 0.05, yv / 90.0 + t * 0.05);",
            "    float2 vw = float2(vnoise(vq * 0.7 + float2(4.0 + tf * 0.03, 1.0)), vnoise(vq * 0.7 + float2(9.0, 6.0 - tf * 0.03)));",
            "    float s = fbm(vq + 0.5 * vw, tf);",
            "    float env = smoothstep(-60.0, 40.0, yv) * (1.0 - smoothstep(120.0, 500.0, yv));",
            "    vv = smoothstep(-0.15, 0.6, s) * env * (fogC.z > 0.5 ? 0.16 : 0.4);",
            "  }",
            "  float3 vcol = fogC.z > 0.5 ? mix(float3(0.42, 0.43, 0.52), accent, 0.2) : mix(float3(1.0), accent, 0.15);",
            "  float aa = vv + a * (1.0 - vv);",
            "  return half4(half3(vcol * vv + c * a * (1.0 - vv) + dither(p) * aa), half(aa));",
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
        Look a = look(src, mode == MIST ? (night ? 2 : 0) : dark ? (night ? 2 : 1) : 0, matte);
        flow(a, src, t, px);
        boolean fresh = acc == null || !moving || now - computedAt > frameMs * 3 + 100; // 隔了一阵才画的（刚滚进来）直接用新的
        if (acc == null) acc = new float[sw * sh * 4];
        float blend = fresh ? 1 : fadeFrom > 0 && now - fadeFrom < FADE_MS ? .12f : .35f;
        if (mode == HEAD) {
            if (px2 == null) px2 = new int[sw * sh];
            Bitmap ls = light != null ? light : src;
            flow(look(ls, night ? 2 : 0, false), ls, t, px2);
            fog(a, look(ls, night ? 2 : 0, false), t / 1000f, blend);
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
            matrix.postTranslate(0, -shift / (cell() * density));
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
     * 详情页的雾：某一格有多浓，看它在边下面多深，再减去一条横向起伏的“淌到多深”（颜色重的一侧整体更深，叠两种
     * 长短不同的波，各自走）和一张流动的噪声（三个倍频，横向拉长，各往不同方向漂，坐标先被一层平滑的慢噪声轻轻扭过，
     * 边是一团团往外翻涌的）。深的一层收得早、边窄一些，浅雾淌得远、边很宽；两层用同一张噪声，深色是在浅雾里化开的。
     * 上面再盖一层水汽：浅色的一片，边很散，往上飘、横着漂；深色上有大块很软的明暗慢慢漂过，整片颜色在呼吸。
     * 三层的边各自跟着正文走（见 parallax）。噪声隔一格算一次（明暗八格一次），中间插值；
     * 用不到噪声的行（深色铺满的、雾和水汽都到不了的）不算。
     */
    private void fog(Look deepLook, Look lightLook, float ts, float blend) {
        float footDp = foot / density, lightDp = fogFoot / density, vaporDp = vaporFoot / density, tf = ts * 2.6f;
        int gw = sw / 2 + 2, gh = sh / 2 + 2;
        if (n1 == null || n1.length != gw * gh) {
            n1 = new float[gw * gh];
            n2 = new float[gw * gh];
            n3 = new float[gw * gh];
        }
        for (int gy = 0; gy < gh; gy++) {
            float yDp = (gy * 2 + .5f) * CELL_DP, y = (yDp - lightDp) / 70, vy = (yDp - vaporDp) / 90 + ts * .05f;
            if (yDp - footDp < -160 && yDp - vaporDp < -80
                    || yDp - footDp > 240 && yDp - lightDp > 660 && yDp - vaporDp > 520) continue;
            for (int gx = 0; gx < gw; gx++) {
                float xDp = (gx * 2 + .5f) * CELL_DP, x = xDp / 120 + fogOff, vx = xDp / 150 + fogOff * 1.3f - ts * .05f;
                float wx = vnoise(x * .6f + 1.7f + tf * .03f, y * .6f + 9.2f), wy = vnoise(x * .6f + 8.3f, y * .6f + 2.8f - tf * .03f);
                float vwx = vnoise(vx * .7f + 4 + tf * .03f, vy * .7f + 1), vwy = vnoise(vx * .7f + 9, vy * .7f + 6 - tf * .03f);
                int g = gy * gw + gx;
                n1[g] = fbm(x + .4f * wx, y + .4f * wy, tf);
                n2[g] = fbm(x * 1.7f + 3.1f + .3f * wx, y * 1.5f + 7 + .3f * wy, tf * 1.3f);
                n3[g] = fbm(vx + .5f * vwx, vy + .5f * vwy, tf);
            }
        }
        int cw = sw / 8 + 2, cgh = sh / 8 + 2; // 深色上飘过的雾很软，八格算一次
        if (n4 == null || n4.length != cw * cgh) n4 = new float[cw * cgh];
        for (int cy = 0; cy < cgh; cy++) {
            float yDp = (cy * 8 + .5f) * CELL_DP;
            if (yDp - footDp > 100) continue;
            for (int cx = 0; cx < cw; cx++) {
                float xDp = (cx * 8 + .5f) * CELL_DP;
                n4[cy * cw + cx] = fbm(xDp / 220 + fogOff * .7f, (yDp + shift / density) / 160, tf * .6f);
            }
        }
        float[] reach = new float[sw];
        for (int x = 0; x < sw; x++) {
            float u = (x + .5f) / sw, lean = side > 0 ? u : 1 - u;
            reach[x] = (60 + 50 * k) * (float) Math.pow(lean, 1.6) + 20 * (float) Math.sin(6.2832 * (.8 * u + r0) + ts / 3.2)
                    + 12 * (float) Math.sin(6.2832 * (2.2 * u + r1) - ts / 2.4);
        }
        float ma = night ? .7f : .36f, vs = night ? .16f : .4f;
        int mv = night ? 0xFF1D1E26 : Color.WHITE, ink = 0xFF08080C;
        int vcol = night ? Theme.mix(0xFF6B6E85, accent, .2f) : Theme.mix(Color.WHITE, accent, .15f);
        float[] d = new float[3], m = new float[3];
        for (int y = 0; y < sh; y++) {
            float yDp = (y + .5f) * CELL_DP, yy = yDp - footDp, yl = yDp - lightDp, yv = yDp - vaporDp;
            float top = clamp(yDp / Math.max(1, footDp - 40));
            float env = smooth(-60, 40, yv) * (1 - smooth(120, 500, yv));
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
            float fade = yl < 0 ? 1 : 1 - .45f * clamp(yl / 360);
            float gyf = y / 2f;
            int gy0 = Math.min(gh - 2, (int) gyf);
            float fy = gyf - gy0;
            for (int x = 0; x < sw; x++) {
                float gxf = x / 2f;
                int gx0 = Math.min(gw - 2, (int) gxf), g00 = gy0 * gw + gx0;
                float fx = gxf - gx0;
                float n = lerp(lerp(n1[g00], n1[g00 + 1], fx), lerp(n1[g00 + gw], n1[g00 + gw + 1], fx), fy);
                float nn = lerp(lerp(n2[g00], n2[g00 + 1], fx), lerp(n2[g00 + gw], n2[g00 + gw + 1], fx), fy);
                float nv = lerp(lerp(n3[g00], n3[g00 + 1], fx), lerp(n3[g00 + gw], n3[g00 + gw + 1], fx), fy);
                float vd = va;
                if (yy < 60) {
                    float cxf = x / 8f, cyf = y / 8f;
                    int cx0 = Math.min(cw - 2, (int) cxf), cy0 = Math.min(cgh - 2, (int) cyf), c00 = cy0 * cw + cx0;
                    float ux = cxf - cx0, uy = cyf - cy0;
                    float cloud = lerp(lerp(n4[c00], n4[c00 + 1], ux), lerp(n4[c00 + cw], n4[c00 + cw + 1], ux), uy);
                    vd = clamp(va - .1f * cloud * (1 - smooth(0, 60, yy)));
                }
                float am = (1 - smooth(-60, 70, yl - 150 - reach[x] - 70 * n)) * fade;
                float ad = 1 - smooth(-40, 45, yy - 20 - .55f * reach[x] - 40 * (.7f * n + .3f * nn));
                int i = y * sw + x, o = i * 4;
                metal(px[i], deepLook.lut, d, 0);
                metal(px2[i], lightLook.lut, m, 0);
                float a = ad + am * (1 - ad), vv = smooth(-.15f, .6f, nv) * env * vs, aa = vv + a * (1 - vv);
                for (int ch = 0; ch < 3; ch++) {
                    int bits = 16 - ch * 8;
                    float mc = m[ch] * (1 - ma) + ((mv >> bits) & 0xFF) * ma;
                    float dc = d[ch] * (1 - vd) + ((vc >> bits) & 0xFF) * vd;
                    float v = a > .001f ? (dc * ad + mc * am * (1 - ad)) / a : mc; // 雾外面的格子也给浅雾的颜色，模糊时边上不发黑
                    if (aa > .001f) v = (((vcol >> bits) & 0xFF) * vv + v * a * (1 - vv)) / aa; // 水汽盖在雾上面
                    acc[o + ch] += (v - acc[o + ch]) * blend;
                }
                acc[o + 3] += (aa * 255 - acc[o + 3]) * blend;
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

    /**
     * 一张图的“金属曲线”：亮度先按这张图自己的亮度范围（5%～95% 分位）归一，同一条曲线在深的浅的图上都起在同样的位置。
     * 哑光的（matte）不起亮脊：暗部按比例压到六成（往上到 65% 那一档恢复），72% 一档附近一层很宽、很弱的光泽。
     */
    private static final class Look {
        final int base;
        final float lo, span, cap; // 这张图的亮度范围（5%～95% 分位）；亮脊封顶到多亮，0 是不封顶
        final boolean matte;
        final float[] lut = new float[1025];
        final BitmapShader shader;

        /** cap：0 不封顶；1 是白字的底，亮脊最多提到 0.58；2 是深色模式的白字底，0.46。 */
        Look(Bitmap b, int cap, boolean matte) {
            this.matte = matte;
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
                float l2;
                if (matte) {
                    float e = clamp(u / .65f);
                    l2 = l * (.6f + .4f * e * e * (3 - 2 * e)) + .175f * span * bell(u, .72f, .24f);
                } else {
                    l2 = l + .5f * span * (1.15f * bell(u, .66f, .1f) - .4f * bell(u, .32f, .16f) + .35f * bell(u, .98f, .08f));
                }
                l2 = Math.max(.01f, Math.min(1, l2));
                if (cap > 0 && l2 > l) l2 = Math.max(l, Math.min(l2, cap == 2 ? .46f : .58f));
                lut[i] = l2 / Math.max(l, .01f);
            }
            shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
    }

    private static Look look(Bitmap b, int cap, boolean matte) {
        Look[] ls = LOOKS.get(b);
        if (ls == null) LOOKS.put(b, ls = new Look[6]);
        int i = cap + (matte ? 3 : 0);
        if (ls[i] == null) ls[i] = new Look(b, cap, matte);
        return ls[i];
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
