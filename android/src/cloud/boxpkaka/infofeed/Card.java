package cloud.boxpkaka.infofeed;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * 像 iOS 的卡片展开：点开列表里的一张卡片，这张卡片原地长成整页。容器从卡片的位置、大小、圆角和底色平滑长到全屏，
 * 卡片的样子淡出、页面内容淡入（都按容器宽度等比缩放、贴着容器顶部，不会各自乱跑），下面的列表稍稍压暗。
 * 关的时候反过来收回到列表里那张卡片上：关之前重新找它、重新拍一张（列表刷新过、滚动过也能对上），找不到或者不在
 * 屏幕上就原地缩小淡出。右滑时整页跟着手指缩小、变圆角，松手过线就收回卡片，否则弹回。
 * 展开和收拢的时候那张卡片藏起来，看起来就是它自己飞出去、又飞回来。卡片滚到了列表边上、一截藏在标签栏下面时，
 * 从露出来的那部分开始长，藏着的那截跟着慢慢露出来，不会一下子盖到标签栏上；收回时也收回到露出来的那部分。
 * 动画途中也不挡手指：展开和弹回的途中可以直接滚页面、点里面的东西、右滑返回；收回途中窗口不接手指，手指落到下面的
 * 列表上，列表可以接着滚，卡片每帧重新找那张卡片、跟着它现在的位置收回去。
 * 打开的一方用 Card.open 把卡片交给新页面（同一进程，放在静态变量里），新页面用 Nav.of 接过来。
 *
 * 专题卡片换一种展开（Hero）：卡片不长成整页，而是长成专题页的标题栏。同一块流动的雾面金属从卡片的位置、大小、圆角
 * 长成标题栏，全程在流动，没有截图；标题单独飞到标题栏的位置、一路放大；卡片上别的字先淡出，标题栏的返回和小字最后淡入；
 * 页面自己的雾和列表跟着变（见 Hero.morph）。收回时倒着走。右滑时整页还是跟着手指缩小，松手后从标题栏当时的样子收回卡片。
 */
final class Card implements Nav {
    /** 关的时候去哪儿找那张卡片：列表复用行的按 id 找；找不到返回 null。 */
    interface Anchor {
        View find();
    }

    /** 能长成标题栏的卡片：用 setTag 挂在卡片上，告诉 Card 哪个是标题（卡片上的 Flow 是那块流动的金属）。 */
    static final class Parts {
        final TextView title;

        Parts(TextView title) {
            this.title = title;
        }
    }

    /** 卡片长成它的标题栏的页面（专题页）：由 Activity 实现。 */
    interface Hero {
        /** 标题栏：展开和收回时藏起来，由动画代画。 */
        View header();

        /** 标题栏里的标题。 */
        TextView title();

        /** 新建一块和标题栏一样的流动金属（同一张图、同一个时钟、同样慢），动画里从卡片长成标题栏的就是它。 */
        Flow flow();

        /** 页面自己的部分跟着 q 变（0 是卡片，1 是整页）：雾从首页的颜色换成专题色，列表依次从下面浮上来。 */
        void morph(float q);
    }

    private static final int SCRIM = 0x33;
    private static final float FULL = 1000; // 展开程度用千分比算弹簧，弹簧在差半个单位时停
    private static Handoff pending;
    private static WeakReference<View> hidden;
    private static WeakReference<Card> hider; // 谁藏的（见 show）

    /** 一张卡片此刻的样子：截图、在屏幕上的位置、圆角、底色，上下被外面裁掉的两截（见 cut）。 */
    private static final class Shot {
        Bitmap bitmap;
        RectF rect;
        float radius, top, bottom;
        int color;
    }

    /** 长成标题栏的卡片此刻的样子：不带金属和标题的字（截图）、卡片和标题在屏幕上的位置、标题的字号（像素）、圆角，上下被裁掉的两截。 */
    private static final class Face {
        Bitmap text;
        RectF rect;
        float titleX, titleY, titleSize, radius, top, bottom;
    }

    private static final class Handoff {
        Class<?> target;
        long time;
        Anchor anchor;
        View source;
        Shot shot;
        Face face;
    }

    private final Activity a;
    private final View page;
    private final int bg, minFling;
    private final Anchor anchor;
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private final Clip pageClip = new Clip(), boxClip = new Clip(), shotClip = new Clip();
    private View source, box;
    private ImageView image;
    private Shot shot;
    private ValueAnimator grow, shrink; // grow 动 q（展开、收拢），shrink 动 d（右滑没过线时弹回）
    private float q, d, from; // q：0 是卡片，1 是整页；d：右滑缩小了多少，0 到 1
    private boolean dragging, leaving, gone, fade, attached;
    // 长成标题栏（见 Hero）：hero 为 null 时是普通的展开
    private Hero hero;
    private Face face;
    private FrameLayout stage; // 那块从卡片长成标题栏的金属，上面是卡片的字和标题栏的返回、小字
    private GradientDrawable stageShape;
    private ImageView faceText;
    private Mirror mirror;
    private TextView flier; // 飞过去的标题
    private Drawable pageBg;
    private final int[] at = new int[2];

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
            h.face = face(card);
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
        hider = null;
    }

    private Card(Activity a, View page, int bg, Handoff h) {
        this.a = a;
        this.page = page;
        this.bg = bg;
        anchor = h.anchor;
        source = h.source;
        shot = h.shot;
        face = h.face;
        hero = face != null && a instanceof Hero ? (Hero) a : null;
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
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE); // 手指落到下面的列表上（见 follow）
        if (grow != null) grow.cancel();
        if (shrink != null) shrink.cancel();
        restore(); // 原来藏起来的那个控件可能已经被列表拿去显示别的了
        source = anchor.find();
        if (hero != null) {
            Face f = source != null && source.isShown() ? face(source) : null; // 滚出屏幕的也收回去，从屏幕边上进去
            if (f != null) {
                face = f;
                moving();
                faceText.setImageBitmap(f.text);
                faceText.setLayoutParams(new FrameLayout.LayoutParams(Math.round(f.rect.width()), Math.round(f.rect.height())));
                hide(source);
                grow = Spring.CARD.fling(q * FULL, 0, -velocity / width() * FULL, x -> {
                    q = x / FULL;
                    place();
                }, () -> {
                    show(source);
                    gone = true;
                    then.run();
                });
                return;
            }
            leaveHero(); // 卡片找不到了：照普通的办法原地缩小淡出
        }
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
            show(source);
            gone = true;
            then.run();
        });
    }

    /**
     * 容器放到页面下面、截图放到上面。动的时候不挡手指：缩放中的页面按它画出来的样子接点按和滚动。
     * 都不设 elevation：Z 一样才按加入顺序画，不然容器会盖在页面上面。
     */
    private void attach() {
        attached = true;
        ViewGroup parent = (ViewGroup) page.getParent();
        if (hero != null) {
            attachHero(parent);
        } else {
            attachShot(parent);
        }
        page.setPivotX(0);
        page.setPivotY(0);
        page.setOutlineProvider(pageClip);
    }

    private void attachShot(ViewGroup parent) {
        box = new View(a);
        box.setBackground(new ColorDrawable(shot.color));
        parent.addView(box, parent.indexOfChild(page), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        image = new ImageView(a);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        image.setImageBitmap(shot.bitmap);
        parent.addView(image, new FrameLayout.LayoutParams(Math.round(shot.rect.width()), Math.round(shot.rect.height())));
        for (View v : new View[]{box, image}) {
            v.setPivotX(0);
            v.setPivotY(0);
        }
        box.setOutlineProvider(boxClip);
        image.setOutlineProvider(shotClip);
    }

    /**
     * 长成标题栏用的几层，都在页面上面：一块流动的金属（圆角跟着 stageShape）、上面是卡片的字（截图）和标题栏的
     * 返回、小字（照着真的画），最上面是飞过去的标题（和标题栏里的标题同样的字，按比例缩放）。
     */
    private void attachHero(ViewGroup parent) {
        stage = new FrameLayout(a);
        stageShape = new GradientDrawable();
        stage.setBackground(stageShape);
        Flow flow = hero.flow();
        flow.corners(stage);
        stage.addView(flow, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        faceText = new ImageView(a);
        faceText.setScaleType(ImageView.ScaleType.FIT_XY);
        faceText.setImageBitmap(face.text);
        stage.addView(faceText, new FrameLayout.LayoutParams(Math.round(face.rect.width()), Math.round(face.rect.height())));
        mirror = new Mirror(a, hero.header(), hero.title());
        stage.addView(mirror, new FrameLayout.LayoutParams(1, 1));
        parent.addView(stage, new FrameLayout.LayoutParams(1, 1));
        TextView title = hero.title();
        flier = new TextView(a);
        flier.setText(title.getText());
        flier.setTextSize(TypedValue.COMPLEX_UNIT_PX, title.getTextSize());
        flier.setTypeface(title.getTypeface());
        flier.setTextColor(title.getCurrentTextColor());
        flier.setLetterSpacing(title.getLetterSpacing());
        flier.setLineSpacing(title.getLineSpacingExtra(), title.getLineSpacingMultiplier());
        flier.setIncludeFontPadding(title.getIncludeFontPadding());
        parent.addView(flier, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        for (View v : new View[]{stage, mirror, flier}) {
            v.setPivotX(0);
            v.setPivotY(0);
        }
    }

    /** 卡片找不到了（收回时）：拿掉长成标题栏的几层，换回普通的展开，页面原样摆好。 */
    private void leaveHero() {
        hero.morph(1);
        hero.header().setAlpha(1f);
        ViewGroup parent = (ViewGroup) page.getParent();
        parent.removeView(stage);
        parent.removeView(flier);
        if (pageBg != null) page.setBackground(pageBg);
        hero = null;
        attach();
    }

    /** 要动了：容器、截图（或长成标题栏的几层）出来，页面按轮廓裁剪。 */
    private void moving() {
        if (!attached) attach();
        if (hero == null) {
            box.setVisibility(View.VISIBLE);
            image.setVisibility(View.VISIBLE);
            box.setClipToOutline(true);
            image.setClipToOutline(true);
        } else if (page.getBackground() != null) { // 页面底色先拿掉：卡片在长的时候，下面的首页透过来，见 Hero.morph
            pageBg = page.getBackground();
            page.setBackground(null);
        }
        page.setClipToOutline(true);
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
        if (hero == null) {
            box.setVisibility(View.GONE);
            image.setVisibility(View.GONE);
        } else if (pageBg != null) {
            page.setBackground(pageBg);
        }
        page.setClipToOutline(false);
        a.getWindow().getDecorView().setBackgroundColor(bg);
    }

    /** 按 q 和 d 摆好容器、页面和截图。 */
    private void place() {
        if (leaving) follow();
        if (hero != null) {
            placeHero();
            return;
        }
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
        // 卡片在列表里被裁掉的上下两截：一开始照样裁掉（直边），越长越露出来
        float ct = fade ? 0 : shot.top * (1 - k), cb = fade ? 0 : shot.bottom * (1 - k);

        float ps = cw / w;
        frame(page, pageClip, l, t, ps, ch, radius, ct, cb);
        frame(box, boxClip, l, t, ps, ch, radius, ct, cb);
        float is = cw / Math.max(1, shot.rect.width());
        frame(image, shotClip, l, t, is, ch, radius, ct, cb);

        // 页面先在截图下面完全显出来，截图再淡出：两层不会同时半透明，封面中途不会变暗
        image.setAlpha(fade ? 0 : 1 - clamp((k - 0.32f) / 0.3f));
        page.setAlpha(clamp((k - 0.05f) / 0.3f));
        box.setAlpha(fade ? clamp(k / 0.5f) : 1);
        // 页面完全显出来以前容器一直是卡片色：页面头部接着卡片的颜色，下面的部分直接从卡片色淡成页面底色，中途不发白
        ((ColorDrawable) box.getBackground()).setColor((int) argb.evaluate(clamp((k - 0.35f) / 0.4f), shot.color, bg));
        a.getWindow().getDecorView().setBackgroundColor(Color.argb(Math.round(SCRIM * k * (1 - 0.6f * d)), 0, 0, 0));
    }

    /**
     * 长成标题栏：页面本身只跟着右滑缩小；那块金属从卡片的位置、大小、圆角（四角一样）长成标题栏（上面两个角是直角、
     * 下面是大圆角）；卡片的字在前 35% 淡出，标题栏的返回和小字在 50%～90% 淡入；标题从卡片上的位置飞到标题栏上、
     * 字号跟着变。停在整页（包括右滑缩小的时候）是真的标题栏，这几层收起来。
     */
    private void placeHero() {
        float w = width(), h = height();
        float sigma = 1 - 0.16f * d, ow = w * sigma, oh = h * sigma;
        float ol = (w - ow) / 2 + d * w * 0.2f, ot = (h - oh) / 2;
        float orad = dp(32) * Math.min(1, d * 4);
        frame(page, pageClip, ol, ot, sigma, oh, orad, 0, 0);
        float k = clamp(q);
        // 没有压暗：首页的雾和列表由页面自己盖住（Hero.morph）；右滑缩小时才压暗一点，和普通的一样
        a.getWindow().getDecorView().setBackgroundColor(Color.argb(
                Math.round(SCRIM * k * Math.min(1, d * 4) * (1 - 0.6f * d)), 0, 0, 0));
        hero.morph(q);
        View header = hero.header();
        boolean show = q < 1 || leaving;
        header.setAlpha(show ? 0 : 1);
        stage.setVisibility(show ? View.VISIBLE : View.GONE);
        flier.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;

        ((View) page.getParent()).getLocationOnScreen(at);
        float px = at[0], py = at[1], s = page.getScaleX();
        RectF from = new RectF(face.rect);
        from.offset(-px, -py);
        RectF to = new RectF(from);
        float[] hr = header.getBackground() instanceof GradientDrawable
                ? Theme.radii((GradientDrawable) header.getBackground()) : new float[8];
        if (header.getWidth() > 0) { // 标题栏此刻在屏幕上的样子（右滑缩小过的也算上）
            header.getLocationOnScreen(at);
            to.set(at[0] - px, at[1] - py, at[0] - px + header.getWidth() * s, at[1] - py + header.getHeight() * s);
        }
        float l = lerp(from.left, to.left, q), t = lerp(from.top, to.top, q);
        int cw = Math.max(1, Math.round(lerp(from.width(), to.width(), q)));
        int ch = Math.max(1, Math.round(lerp(from.height(), to.height(), q)));
        ViewGroup.LayoutParams lp = stage.getLayoutParams();
        if (lp.width != cw || lp.height != ch) {
            lp.width = cw;
            lp.height = ch;
            stage.setLayoutParams(lp);
        }
        stage.setTranslationX(l);
        stage.setTranslationY(t);
        float top = Math.max(0, lerp(face.radius, Math.max(hr[0] * s, orad), q));
        float bottom = Math.max(0, lerp(face.radius, hr[4] * s, q));
        stageShape.setCornerRadii(new float[]{top, top, top, top, bottom, bottom, bottom, bottom});
        float ct = face.top * (1 - k), cb = face.bottom * (1 - k); // 卡片在列表里被裁掉的上下两截，见 place
        boolean clipped = ct > 0 || cb > 0;
        stage.setClipBounds(clipped ? new Rect(0, Math.round(ct), Integer.MAX_VALUE, Math.round(ch - cb)) : null);

        faceText.setAlpha(1 - clamp(q / 0.35f));
        ViewGroup.LayoutParams mp = mirror.getLayoutParams();
        if (mp.width != header.getWidth() || mp.height != header.getHeight()) {
            mp.width = Math.max(1, header.getWidth());
            mp.height = Math.max(1, header.getHeight());
            mirror.setLayoutParams(mp);
        }
        mirror.setScaleX(s);
        mirror.setScaleY(s);
        mirror.setAlpha(clamp((q - 0.5f) / 0.4f));
        mirror.invalidate();

        TextView title = hero.title();
        int tw = Math.max(1, title.getWidth() - title.getPaddingLeft() - title.getPaddingRight());
        ViewGroup.LayoutParams fp = flier.getLayoutParams();
        if (title.getWidth() > 0 && fp.width != tw) { // 和标题栏里一样宽，折行也一样
            fp.width = tw;
            flier.setLayoutParams(fp);
        }
        float ex = face.titleX - px, ey = face.titleY - py, es = face.titleSize / title.getTextSize();
        float fx = ex, fy = ey, fs = es;
        if (title.getWidth() > 0) {
            title.getLocationOnScreen(at);
            fx = at[0] - px + title.getPaddingLeft() * s;
            fy = at[1] - py + title.getPaddingTop() * s;
            fs = s;
        }
        float ft = lerp(ey, fy, q), fsc = lerp(es, fs, q);
        flier.setTranslationX(lerp(ex, fx, q));
        flier.setTranslationY(ft);
        flier.setScaleX(fsc);
        flier.setScaleY(fsc);
        flier.setClipBounds(clipped ? new Rect(0, Math.round((t + ct - ft) / fsc), Integer.MAX_VALUE,
                Math.round((t + ch - cb - ft) / fsc)) : null); // 标题藏着的那截也和金属一起裁
    }

    /**
     * 控件左上角放在 (l, t)、等比缩放 scale、只露出高 ch（屏幕上的像素）、圆角 radius；上下再用直边裁掉 top、bottom
     * （屏幕上的像素，左右不裁，圆角轮廓已经裁了）。
     */
    private static void frame(View v, Clip clip, float l, float t, float scale, float ch, float radius, float top, float bottom) {
        v.setTranslationX(l);
        v.setTranslationY(t);
        v.setScaleX(scale);
        v.setScaleY(scale);
        clip.height = ch / scale;
        clip.radius = radius / scale;
        v.invalidateOutline();
        v.setClipBounds(top > 0 || bottom > 0
                ? new Rect(0, Math.round(top / scale), Integer.MAX_VALUE, Math.round((ch - bottom) / scale)) : null);
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

    /**
     * 收回途中下面的列表可能在滚：重新找那张卡片，收回的终点跟着它现在的位置和被裁掉的部分走。行被列表拿去显示别的了
     * 就放回来、换成新找到的那行；整行滚出去找不到了，就收到最后看到它的地方，从它出去的那一边整个裁掉。原地淡出的不用找。
     */
    private void follow() {
        if (hero == null && fade) return;
        View now = anchor.find();
        if (now != null && (!now.isAttachedToWindow() || now.getParent() == null)) now = null;
        if (now != source) {
            show(source);
            if (now == null && source != null) lost();
            source = now;
            hide(now);
        }
        if (source == null) return;
        int[] p = new int[2];
        source.getLocationOnScreen(p);
        RectF r = hero != null ? face.rect : shot.rect;
        float dx = p[0] - r.left, dy = p[1] - r.top;
        r.offset(dx, dy);
        float[] hid = cut(source, r);
        if (hero != null) {
            face.titleX += dx;
            face.titleY += dy;
            face.top = hid[0];
            face.bottom = hid[1];
        } else {
            shot.top = hid[0];
            shot.bottom = hid[1];
        }
    }

    /** 那张卡片整行滚出去了：被裁掉多的那一边整个裁掉，收回时收进列表边上。 */
    private void lost() {
        if (hero != null) {
            if (face.top >= face.bottom) face.top = face.rect.height();
            else face.bottom = face.rect.height();
        } else {
            if (shot.top >= shot.bottom) shot.top = shot.rect.height();
            else shot.bottom = shot.rect.height();
        }
    }

    private void hide(View v) {
        if (v == null) return;
        v.setAlpha(0f);
        hidden = new WeakReference<>(v);
        hider = new WeakReference<>(this);
    }

    /** 收完了，藏着的卡片放回来；收回途中又点开了同一张、它已经被新的页面藏起来了，就留给那一页。 */
    private void show(View v) {
        boolean mine = hider != null && hider.get() == this;
        boolean taken = !mine && hidden != null && hidden.get() == v;
        if (v != null && !taken) v.setAlpha(1f);
        if (mine) {
            hidden = null;
            hider = null;
        }
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
        float[] hid = cut(v, s.rect);
        s.top = hid[0];
        s.bottom = hid[1];
        s.bitmap = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(s.bitmap)); // draw() 不管控件自己的 alpha，藏着的卡片也拍得到
        s.radius = radius(v);
        int c = s.bitmap.getPixel(s.bitmap.getWidth() / 2, Math.max(0, s.bitmap.getHeight() - 5)); // 底下取色：上面可能是封面
        s.color = Color.alpha(c) == 255 ? c : Color.TRANSPARENT;
        return s;
    }

    /** 能长成标题栏的卡片（挂着 Parts）此刻的样子；不是的返回 null。按下去还没弹回的卡片按屏幕上的大小算。 */
    private static Face face(View v) {
        if (!(v.getTag() instanceof Parts) || !v.isAttachedToWindow() || v.getWidth() == 0 || v.getHeight() == 0) return null;
        TextView title = ((Parts) v.getTag()).title;
        Face f = new Face();
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        float s = v.getScaleX();
        f.rect = new RectF(at[0], at[1], at[0] + v.getWidth() * s, at[1] + v.getHeight() * s);
        float[] hid = cut(v, f.rect);
        f.top = hid[0];
        f.bottom = hid[1];
        f.text = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        drawExcept(new Canvas(f.text), v, false, title);
        title.getLocationOnScreen(at);
        f.titleX = at[0] + title.getPaddingLeft() * s;
        f.titleY = at[1] + title.getPaddingTop() * s;
        f.titleSize = title.getTextSize() * s;
        f.radius = radius(v) * s;
        return f;
    }

    /**
     * 卡片（屏幕上在 rect）被外面的容器裁掉了多少：滚到了列表上沿、一截藏在标签栏下面，或者下面出了屏幕。
     * 返回 {上面, 下面}，屏幕上的像素；整张都看不见时当作上面全裁掉。
     */
    private static float[] cut(View v, RectF rect) {
        Rect seen = new Rect();
        if (!v.getGlobalVisibleRect(seen)) return new float[]{rect.height(), 0};
        int[] win = new int[2], screen = new int[2];
        v.getLocationInWindow(win);
        v.getLocationOnScreen(screen);
        float dy = screen[1] - win[1]; // 看得见的部分是按窗口算的，换成屏幕上的
        return new float[]{Math.max(0, seen.top + dy - rect.top), Math.max(0, rect.bottom - seen.bottom - dy)};
    }

    /**
     * 把 v 画到 c 上（c 的原点在 v 的左上角），跳过 skip 和流动的金属（Flow）；background 为 false 时不画 v 自己的背景。
     * 拍卡片上的字、照着标题栏画返回和小字都用它。
     */
    private static void drawExcept(Canvas c, View v, boolean background, View skip) {
        if (v instanceof Flow) return;
        if (!(v instanceof ViewGroup) || !holds(v, skip)) {
            v.draw(c);
            return;
        }
        Drawable bg = v.getBackground();
        if (background && bg != null) bg.draw(c);
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View child = g.getChildAt(i);
            if (child == skip || child.getVisibility() != View.VISIBLE) continue;
            int save = c.save();
            c.translate(child.getLeft() + child.getTranslationX(), child.getTop() + child.getTranslationY());
            if (child.getAlpha() < 1) c.saveLayerAlpha(0, 0, child.getWidth(), child.getHeight(), Math.round(255 * child.getAlpha()));
            drawExcept(c, child, true, skip);
            c.restoreToCount(save);
        }
    }

    /** x 是不是 v 自己或者 v 里面的控件。 */
    private static boolean holds(View v, View x) {
        for (View p = x; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == v) return true;
        }
        return false;
    }

    /** 标题栏上除了金属和标题以外的东西（返回、小字）：每帧照着真的画，数据回来后小字也跟着出现。 */
    private static final class Mirror extends View {
        private final View src, skip;

        Mirror(Context c, View src, View skip) {
            super(c);
            this.src = src;
            this.skip = skip;
        }

        @Override
        protected void onDraw(Canvas c) {
            drawExcept(c, src, false, skip);
        }
    }

    /** 卡片背景的圆角（四个角取平均），不是圆角背景就当 16dp。 */
    private static float radius(View v) {
        Drawable bg = v.getBackground();
        if (bg instanceof RippleDrawable && ((RippleDrawable) bg).getNumberOfLayers() > 0) {
            bg = ((RippleDrawable) bg).getDrawable(0);
        }
        if (bg instanceof GradientDrawable) {
            float[] r = Theme.radii((GradientDrawable) bg);
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
