package cloud.boxpkaka.infofeed;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.ref.WeakReference;

/**
 * 像 iOS 的卡片展开：点开列表里的一张卡片，这张卡片原地长成整页。容器从卡片的位置、大小、圆角和底色平滑长到全屏，
 * 卡片的样子淡出、页面内容淡入（都按容器宽度等比缩放、贴着容器顶部，不会各自乱跑），下面的列表稍稍压暗。
 * 关的时候反过来收回到列表里那张卡片上：关之前重新找它、重新拍一张（列表刷新过、滚动过也能对上），找不到或者不在
 * 屏幕上就原地缩小淡出。右滑时整页跟着手指缩小、变圆角，松手过线就收回卡片，否则弹回。
 * 展开和收拢的时候那张卡片藏起来，看起来就是它自己飞出去、又飞回来。
 * 打开的一方用 Card.open 把卡片交给新页面（同一进程，放在静态变量里），新页面用 Nav.of 接过来。
 */
final class Card implements Nav {
    /** 关的时候去哪儿找那张卡片：列表复用行的按 id 找；找不到返回 null。 */
    interface Anchor {
        View find();
    }

    private static final int SCRIM = 0x33;
    private static final float FULL = 1000; // 展开程度用千分比算弹簧，弹簧在差半个单位时停
    private static Handoff pending;
    private static WeakReference<View> hidden;

    /** 一张卡片此刻的样子：截图、在屏幕上的位置、圆角、底色。 */
    private static final class Shot {
        Bitmap bitmap;
        RectF rect;
        float radius;
        int color;
    }

    private static final class Handoff {
        Class<?> target;
        long time;
        Anchor anchor;
        View source;
        Shot shot;
    }

    private final Activity a;
    private final View page;
    private final int bg, minFling;
    private final Anchor anchor;
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private final Clip pageClip = new Clip(), boxClip = new Clip(), shotClip = new Clip();
    private View source, box, shield;
    private ImageView image;
    private Shot shot;
    private ValueAnimator grow, shrink; // grow 动 q（展开、收拢），shrink 动 d（右滑没过线时弹回）
    private float q, d, from; // q：0 是卡片，1 是整页；d：右滑缩小了多少，0 到 1
    private boolean dragging, leaving, gone, fade;

    /** 打开 i 指向的页面，从 card 展开；card 是不会被列表复用的普通控件。 */
    static void open(Activity from, View card, Intent i) {
        open(from, card, weak(card), i, -1);
    }

    static void open(Activity from, View card, Intent i, int request) {
        open(from, card, weak(card), i, request);
    }

    /** anchor：关的时候怎么重新找到这张卡片；request 不小于 0 时用 startActivityForResult。 */
    static void open(Activity from, View card, Anchor anchor, Intent i, int request) {
        Shot s = capture(card);
        if (s != null && i.getComponent() != null) {
            Handoff h = new Handoff();
            try {
                h.target = Class.forName(i.getComponent().getClassName());
            } catch (ClassNotFoundException e) {
                h.target = null;
            }
            h.time = SystemClock.uptimeMillis();
            h.anchor = anchor;
            h.source = card;
            h.shot = s;
            pending = h;
        }
        if (request >= 0) from.startActivityForResult(i, request);
        else from.startActivity(i);
        from.overridePendingTransition(0, 0);
    }

    private static Anchor weak(View v) {
        WeakReference<View> ref = new WeakReference<>(v);
        return ref::get;
    }

    /** 新页面接过交接来的卡片；不是给它的（或者交接太久了）就是 null，由 Nav.of 改用 Push。 */
    static Card take(Activity a, View page, int bg) {
        Handoff h = pending;
        pending = null;
        if (h == null || h.target != a.getClass() || SystemClock.uptimeMillis() - h.time > 3000) return null;
        return new Card(a, page, bg, h);
    }

    /** 藏着的卡片放回来（见 Push.Stack）。 */
    static void restore() {
        View v = hidden == null ? null : hidden.get();
        if (v != null) v.setAlpha(1f);
        hidden = null;
    }

    private Card(Activity a, View page, int bg, Handoff h) {
        this.a = a;
        this.page = page;
        this.bg = bg;
        anchor = h.anchor;
        source = h.source;
        shot = h.shot;
        minFling = ViewConfiguration.get(a).getScaledMinimumFlingVelocity() * 8;
        q = 1;
    }

    @Override
    public Swipe wrap(boolean fresh) {
        Swipe s = new Swipe(a, new Swipe.Pages() {
            @Override
            public boolean has(int dir) {
                return dir < 0 && can();
            }

            @Override
            public void grab(int dir) {
                Card.this.grab();
            }

            @Override
            public void drag(float dx) {
                Card.this.drag(dx);
            }

            @Override
            public void release(float vx) {
                Card.this.release(vx);
            }
        });
        s.addView(page, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        if (fresh) enter();
        return s;
    }

    /** 从卡片展开：第一帧画之前就摆成卡片的样子，等第一帧要画时开始长。 */
    @Override
    public void enter() {
        if (Build.VERSION.SDK_INT >= 34) {
            a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0);
            a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
        } else {
            a.overridePendingTransition(0, 0);
        }
        q = 0;
        moving();
        place();
        page.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                page.getViewTreeObserver().removeOnPreDrawListener(this);
                if (leaving) return true;
                place(); // 有了真实大小再摆一次
                hide(source);
                grow = Spring.CARD.fling(0, FULL, 0, x -> {
                    q = x / FULL;
                    place();
                }, Card.this::settled);
                return true;
            }
        });
    }

    @Override
    public boolean can() {
        return !leaving;
    }

    /** 展开途中也可以接住：展开接着走，手指只管缩小。 */
    @Override
    public void grab() {
        if (leaving) return;
        if (shrink != null) shrink.cancel();
        dragging = true;
        moving();
        // 页面自己回到前台时卡片被放出来过；缩小会露出下面的列表，先再藏起来
        View v = anchor.find();
        if (v != null) {
            restore();
            source = v;
            hide(v);
        }
        from = d;
    }

    @Override
    public void drag(float dx) {
        if (leaving) return;
        d = Math.max(0, Math.min(1, from + dx / width()));
        place();
    }

    @Override
    public void release(float vx) {
        if (leaving) return;
        dragging = false;
        if (vx > minFling || d > 0.3f && vx > -minFling) {
            close(vx, a::onBackPressed);
            return;
        }
        shrink = Spring.PAGE.fling(d * FULL, 0, vx / width() * FULL, x -> {
            d = Math.max(0, x / FULL);
            place();
        }, this::settled);
    }

    @Override
    public void finish(Runnable done) {
        if (gone) {
            done.run();
            a.overridePendingTransition(0, 0);
            return;
        }
        if (!leaving) close(0, () -> finish(done));
    }

    @Override
    public void replaced() {
        gone = true;
    }

    /** 收回卡片：重新找它、拍一张，在屏幕上就收回去，不然原地缩小淡出。velocity 是松手时往右的速度。 */
    private void close(float velocity, Runnable then) {
        leaving = true;
        if (grow != null) grow.cancel();
        if (shrink != null) shrink.cancel();
        restore(); // 原来藏起来的那个控件可能已经被列表拿去显示别的了
        source = anchor.find();
        Shot s = onScreen(source) ? capture(source) : null;
        fade = s == null;
        if (s != null) {
            shot = s;
            image.setImageBitmap(s.bitmap);
            image.setLayoutParams(new FrameLayout.LayoutParams(Math.round(s.rect.width()), Math.round(s.rect.height())));
            hide(source);
        } else {
            source = null;
        }
        moving();
        grow = Spring.CARD.fling(q * FULL, 0, -velocity / width() * FULL, x -> {
            q = x / FULL;
            place();
        }, () -> {
            if (source != null) source.setAlpha(1f);
            hidden = null;
            gone = true;
            then.run();
        });
    }

    /**
     * 容器放到页面下面、截图放到上面，最上面一层透明的挡板在动的时候挡住点按（免得点到缩放中的内容）。
     * 都不设 elevation：Z 一样才按加入顺序画，不然容器会盖在页面上面。
     */
    private void attach() {
        ViewGroup parent = (ViewGroup) page.getParent();
        box = new View(a);
        box.setBackground(new ColorDrawable(shot.color));
        parent.addView(box, parent.indexOfChild(page), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        image = new ImageView(a);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        image.setImageBitmap(shot.bitmap);
        parent.addView(image, new FrameLayout.LayoutParams(Math.round(shot.rect.width()), Math.round(shot.rect.height())));
        shield = new View(a);
        shield.setClickable(true);
        parent.addView(shield, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        for (View v : new View[]{box, page, image}) {
            v.setPivotX(0);
            v.setPivotY(0);
        }
        box.setOutlineProvider(boxClip);
        page.setOutlineProvider(pageClip);
        image.setOutlineProvider(shotClip);
    }

    /** 要动了：容器、截图和挡板出来，页面按轮廓裁剪。 */
    private void moving() {
        if (box == null) attach();
        box.setVisibility(View.VISIBLE);
        image.setVisibility(View.VISIBLE);
        shield.setVisibility(View.VISIBLE);
        box.setClipToOutline(true);
        page.setClipToOutline(true);
        image.setClipToOutline(true);
    }

    /** 一个动画停了：展开和弹回都停了、手也没按着，才算停在整页。 */
    private void settled() {
        boolean busy = grow != null && grow.isRunning() || shrink != null && shrink.isRunning();
        if (!busy && !dragging && !leaving) rest();
    }

    /** 停在整页：拿掉容器和截图，不再裁剪，窗口底色换回页面底色。 */
    private void rest() {
        q = 1;
        d = 0;
        place();
        box.setVisibility(View.GONE);
        image.setVisibility(View.GONE);
        shield.setVisibility(View.GONE);
        page.setClipToOutline(false);
        a.getWindow().getDecorView().setBackgroundColor(bg);
    }

    /** 按 q 和 d 摆好容器、页面和截图。 */
    private void place() {
        float w = width(), h = height();
        // 展开后的样子：整屏；右滑时往中间缩一点、往右跟一点、变圆角
        float sigma = 1 - 0.16f * d, ow = w * sigma, oh = h * sigma;
        float ol = (w - ow) / 2 + d * w * 0.2f, ot = (h - oh) / 2;
        float orad = dp(32) * Math.min(1, d * 4);
        RectF s = fade ? new RectF(ol + ow * 0.1f, ot + oh * 0.1f, ol + ow * 0.9f, ot + oh * 0.9f) : local(shot.rect);
        float l = lerp(s.left, ol, q), t = lerp(s.top, ot, q);
        float cw = Math.max(1, lerp(s.width(), ow, q)), ch = Math.max(1, lerp(s.height(), oh, q));
        float radius = Math.max(0, lerp(fade ? dp(24) : shot.radius, orad, q));
        float k = Math.max(0, Math.min(1, q));

        float ps = cw / w;
        frame(page, pageClip, l, t, ps, ch, radius);
        frame(box, boxClip, l, t, ps, ch, radius);
        float is = cw / Math.max(1, shot.rect.width());
        frame(image, shotClip, l, t, is, ch, radius);

        image.setAlpha(fade ? 0 : 1 - clamp((k - 0.1f) / 0.3f));
        page.setAlpha(clamp((k - 0.2f) / 0.4f));
        box.setAlpha(fade ? clamp(k / 0.5f) : 1);
        ((ColorDrawable) box.getBackground()).setColor((int) argb.evaluate(clamp(k / 0.6f), shot.color, bg));
        a.getWindow().getDecorView().setBackgroundColor(Color.argb(Math.round(SCRIM * k * (1 - 0.6f * d)), 0, 0, 0));
    }

    /** 控件左上角放在 (l, t)、等比缩放 scale、只露出高 ch（屏幕上的像素）、圆角 radius。 */
    private static void frame(View v, Clip clip, float l, float t, float scale, float ch, float radius) {
        v.setTranslationX(l);
        v.setTranslationY(t);
        v.setScaleX(scale);
        v.setScaleY(scale);
        clip.height = ch / scale;
        clip.radius = radius / scale;
        v.invalidateOutline();
    }

    /** 屏幕坐标换成页面所在外框里的坐标（页面的位移相对外框算；Android 10 上外框在状态栏下面）。 */
    private RectF local(RectF screen) {
        int[] at = new int[2];
        ((View) page.getParent()).getLocationOnScreen(at);
        RectF r = new RectF(screen);
        r.offset(-at[0], -at[1]);
        return r;
    }

    private float width() {
        int w = page.getWidth();
        return w > 0 ? w : a.getResources().getDisplayMetrics().widthPixels;
    }

    private float height() {
        int h = page.getHeight();
        return h > 0 ? h : a.getResources().getDisplayMetrics().heightPixels;
    }

    private float dp(float v) {
        return v * a.getResources().getDisplayMetrics().density;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp(float x) {
        return Math.max(0, Math.min(1, x));
    }

    private static void hide(View v) {
        if (v == null) return;
        v.setAlpha(0f);
        hidden = new WeakReference<>(v);
    }

    /** 卡片还挂在界面上、看得见，而且有一部分在屏幕里。 */
    private boolean onScreen(View v) {
        if (v == null || !v.isAttachedToWindow() || !v.isShown() || v.getWidth() == 0) return false;
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        int screen = a.getResources().getDisplayMetrics().heightPixels;
        return at[1] + v.getHeight() > 0 && at[1] < screen;
    }

    private static Shot capture(View v) {
        if (v == null || !v.isAttachedToWindow() || v.getWidth() == 0 || v.getHeight() == 0) return null;
        Shot s = new Shot();
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        s.rect = new RectF(at[0], at[1], at[0] + v.getWidth(), at[1] + v.getHeight());
        s.bitmap = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(s.bitmap)); // draw() 不管控件自己的 alpha，藏着的卡片也拍得到
        s.radius = radius(v);
        int c = s.bitmap.getPixel(s.bitmap.getWidth() / 2, Math.min(s.bitmap.getHeight() - 1, 4));
        s.color = Color.alpha(c) == 255 ? c : Color.TRANSPARENT;
        return s;
    }

    /** 卡片背景的圆角（四个角取平均），不是圆角背景就当 16dp。 */
    private static float radius(View v) {
        Drawable bg = v.getBackground();
        if (bg instanceof RippleDrawable && ((RippleDrawable) bg).getNumberOfLayers() > 0) {
            bg = ((RippleDrawable) bg).getDrawable(0);
        }
        if (bg instanceof GradientDrawable) {
            GradientDrawable g = (GradientDrawable) bg;
            float[] r = g.getCornerRadii();
            if (r == null) return g.getCornerRadius();
            float sum = 0;
            for (float x : r) sum += x;
            return sum / r.length;
        }
        return 16 * v.getResources().getDisplayMetrics().density;
    }

    /** 圆角矩形轮廓，只露出上面 height 那么高。 */
    private static final class Clip extends ViewOutlineProvider {
        float height, radius;

        @Override
        public void getOutline(View v, Outline o) {
            o.setRoundRect(0, 0, v.getWidth(), Math.max(1, Math.round(height)), radius);
        }
    }
}
