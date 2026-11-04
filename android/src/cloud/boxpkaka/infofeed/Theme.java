package cloud.boxpkaka.infofeed;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 视觉规范（雾面金属，见 docs/design.md）：整个 App 只有一种材质，按浓淡分三级——金属是门面（最值得看、专题卡片和标题栏、
 * 详情页头部、报告的要点，见 Flow）和选中的状态（标签、“新”“要闻”，静止的金属小块，见 metal）；雾是每一页的底（Flow.mist，
 * 同一页颜色的亮色版本）；磨砂是雾上面的行、标签栏、按钮（frost：半透明，带颗粒和一圈细倒角）。
 * 钴蓝是主色，表示新的、有变化的；酸橙只给“问一问”和发送；墨色给“要闻”和选中的标签。
 * 话题有自己的颜色（zone），首页、专题、专题页、详情页是同一种颜色。颜色一律按用途取，深浅色各一套。
 * 层次靠字重而不是颜色：900 大标题、800 卡片标题、700 列表、400 正文。数字、时间和字标用 Roboto Flex，
 * 加宽的放大数字，收窄的放来源和时间。
 */
final class Theme {
    /** 圆角档位（dp）：分段列表内侧、按下、选中、问一问、分段列表外侧、卡片和面板。 */
    static final float R_XS = 8, R_S = 12, R_M = 16, R_L = 22, R_XL = 28, R_2XL = 32;

    private static final Map<String, Typeface> FLEX = new HashMap<>();

    /** 不属于某个话题的颜色：报告用的主色钴蓝、首页和专题列表的珍珠银（带一点点紫）。用作 zone 的 key。 */
    static final String PRI = "\u0001pri", PEARL = "\u0001pearl";

    final boolean night;
    final int bg, sheet, hi, ink, sub, line, chip;
    final int pri, onPri, pc, onPc, lime, onLime, inv, onInv, invPri, err, onErr;
    /** 磨砂：行和标签栏的底、按钮的底（更不透明一些，浮在内容上面也看得清）。 */
    final int frost, frostButton;
    private final float density;
    private final Finish frostFinish, metalFinish;

    Theme(Context c) {
        night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF121319 : 0xFFF3F3F8;
        sheet = night ? 0xFF1D1E26 : 0xFFFFFFFF; // 卡片、列表项、输入框
        hi = night ? 0xFF282A33 : 0xFFE8E8F0; // 未选中的按钮、骨架屏
        ink = night ? 0xFFE6E6EF : 0xFF15161F;
        sub = night ? 0xFFA2A4B6 : 0xFF5E6072;
        line = night ? 0xFF454756 : 0xFFC6C7D6; // 拖动条、时间线
        chip = night ? 0x1FE6E6EF : 0x1215161F;
        pri = night ? 0xFFB9C0FF : 0xFF3443F5;
        onPri = night ? 0xFF0D1A86 : 0xFFFFFFFF;
        pc = night ? 0xFF283180 : 0xFFDCE0FF;
        onPc = night ? 0xFFDCE0FF : 0xFF0E1C8F;
        lime = night ? 0xFFC4E65A : 0xFFD6F36B;
        onLime = night ? 0xFF1C2A00 : 0xFF1F2E00;
        inv = night ? 0xFFE6E6EF : 0xFF2C2D36; // 底部工具栏、撤销条、更新提示
        onInv = night ? 0xFF22232B : 0xFFF1F1F6;
        invPri = night ? 0xFF3443F5 : 0xFFB9C0FF;
        err = night ? 0xFF6E1F14 : 0xFFFFDAD5; // 右滑“不需要”、连接断开
        onErr = night ? 0xFFFFDAD5 : 0xFF5C0C02;
        frost = night ? 0x85282936 : 0x99FFFFFF;
        frostButton = night ? 0xA82C2D3A : 0xBDFFFFFF;
        density = c.getResources().getDisplayMetrics().density;
        // 颗粒：磨砂是浅底（深色模式是深底），金属小块深浅都有，按中间算
        frostFinish = new Finish(c, new Flow.Grain(c).set(night ? .13f : .11f, night ? .16f : .93f),
                night ? 0x17FFFFFF : 0xF2FFFFFF, night ? 0x66000000 : 0x121E1E46);
        metalFinish = new Finish(c, new Flow.Grain(c).set(.07f, .5f), 0x4DFFFFFF, 0x33000000);
    }

    /**
     * 事件的主话题（服务器让模型判断，见 catchup/pipeline/brief.py 的 assign_topics）；“其他”和还没判断的返回空串，
     * 此时不写话题。
     */
    static String topicOf(JSONObject e) {
        String s = e == null || e.isNull("topic") ? "" : e.optString("topic");
        return "其他".equals(s) ? "" : s;
    }

    /**
     * 专题的颜色 {底色, 文字色, 形状色}，按专题的 key（兴趣名或榜单来源名）。用在专题页的卡片、专题标题栏和信息流里
     * 没有封面的“最值得看”卡片上，列表行里不出现；避开了钴蓝（新）、酸橙（问一问）和红色（不需要）。没登记的专题用卡片色。
     */
    int[] zone(String key) {
        switch (key) {
            case "AI 语音":
                return night ? new int[]{0xFF5B1F3C, 0xFFFFD9E6, 0xFFFF8AB4} : new int[]{0xFFFFD9E6, 0xFF5A0B33, 0xFFE5487F};
            case "大模型":
                return night ? new int[]{0xFF3A2B78, 0xFFE9DFFF, 0xFFB7A2FF} : new int[]{0xFFE9DFFF, 0xFF2C186B, 0xFF7D5CF6};
            case "Agent":
                return night ? new int[]{0xFF5A3710, 0xFFFFE2BF, 0xFFFFB45A} : new int[]{0xFFFFE2BF, 0xFF4A2800, 0xFFF08A12};
            case "github_trending":
                return night ? new int[]{0xFF0E4836, 0xFFCBF1E0, 0xFF5FD9AA} : new int[]{0xFFCBF1E0, 0xFF003828, 0xFF18A877};
            case "hf_models":
                return night ? new int[]{0xFF4A3D00, 0xFFFFF0AE, 0xFFF2CC3D} : new int[]{0xFFFFF0AE, 0xFF3A2E00, 0xFFD9AA00};
            case "product_hunt":
                return night ? new int[]{0xFF0D3D57, 0xFFD2ECFF, 0xFF6CC4F2} : new int[]{0xFFD2ECFF, 0xFF00324A, 0xFF1E9BD7};
            case "AI 创投":
                return night ? new int[]{0xFF4E1A5A, 0xFFF8D8FF, 0xFFE48BF5} : new int[]{0xFFF8D8FF, 0xFF4A0057, 0xFFC23BD4};
            case "papers": // 论文：纸张的暖灰
                return night ? new int[]{0xFF3D372C, 0xFFF0E8D8, 0xFFCDB68A} : new int[]{0xFFECE6DA, 0xFF2F2A20, 0xFF8F7A55};
            case PRI:
                return night ? new int[]{0xFF283180, 0xFFDCE0FF, 0xFFB9C0FF} : new int[]{0xFFDCE0FF, 0xFF0E1C8F, 0xFF3443F5};
            case PEARL:
                return night ? new int[]{0xFF24242E, 0xFFECEAF4, 0xFF8E8BB0} : new int[]{0xFFECEAF4, 0xFF2C2D36, 0xFF8E8BB0};
            default:
                return new int[]{sheet, ink, sub};
        }
    }

    /**
     * 话题（或主色、珍珠银）的金属卡片配色 {上, 下, 文字色, 点缀色}：浅色模式也是深底白字（和多数封面卡片轻重一致），
     * 色相是话题的形状色；深色模式是话题的深色本色。没登记的话题用珍珠银。
     */
    int[] field(String key) {
        int[] z = zone(key);
        if (z[0] == sheet && !PEARL.equals(key)) return field(PEARL);
        if (night) return new int[]{z[0], mix(z[0], z[2], 0.22f), z[1], z[2]};
        float[] h = new float[3];
        Color.colorToHSV(z[2], h);
        return new int[]{hsv(h[0], Math.min(h[1], 0.6f), 0.4f), hsv(h[0], Math.min(h[1], 0.7f), 0.26f), Color.WHITE,
                hsv(h[0], Math.min(h[1], 0.4f), 1)};
    }

    /**
     * 抽象封面（Flow.art）的六种颜色：底色、形状色、两边各偏一点色相的邻色、一块深色、一块高光。pastel 是只有亮色的那张
     * （浅色模式的雾和详情页往下淌的浅雾），冲淡了也是干净的同色系；珍珠银只带一点点色。橙、黄这类暖色只往黄绿偏一点，免得发灰。
     */
    int[] artColors(String key, boolean pastel) {
        int[] z = zone(key);
        if (z[0] == sheet && !PEARL.equals(key)) z = zone(PEARL);
        float[] c = new float[3];
        Color.colorToHSV(z[2], c);
        float h = c[0], s = c[1], v = c[2], up = h > 15 && h < 75 ? 12 : 30;
        if (pastel) {
            float p = PEARL.equals(key) ? .45f : 1;
            return new int[]{hsv(h, .16f * p, 1), hsv(h, .5f * p, 1), hsv(h + up, .4f * p, 1), hsv(h - 30, .34f * p, 1),
                    hsv(h, .62f * p, .94f), Color.WHITE};
        }
        if (!night) {
            return new int[]{hsv(h, .62f, .34f), hsv(h, s, .8f), hsv(h + up, s * .85f, .86f), hsv(h - 30, s, .56f),
                    hsv(h, .7f, .14f), hsv(h, .3f, .96f)};
        }
        return new int[]{z[0], z[2], hsv(h + up, s * .9f, v * .85f), hsv(h - 32, Math.min(1, s * 1.1f), v * .62f),
                mix(z[0], Color.BLACK, .55f), mix(z[2], Color.WHITE, .35f)};
    }

    /**
     * 照着封面给卡片配色 {上, 下, 文字色, 点缀色}，像音乐软件按专辑封面铺背景：上面接着图底边的颜色（edge），往下渐变到
     * 封面的主色（main），两种颜色都是服务器算好随信息流给的（见 catchup/images.py 的 palette）。图底边偏暗的
     * （深色模式下全部）配深底白字，偏亮的配浅底深字；深浅、浓淡收在好读的范围里，色相还是封面的。
     * 点缀色（序号）的鲜艳程度（饱和度 × 亮度）不超过封面本身：灰的封面配带一点点色的灰白，不会冒出比底色还鲜艳的颜色。
     */
    int[] cover(int edge, int main) {
        float[] e = new float[3], m = new float[3];
        Color.colorToHSV(edge, e);
        Color.colorToHSV(main, m);
        if (night || Color.luminance(edge) < 0.4f) {
            return new int[]{hsv(e[0], Math.min(e[1], 0.6f), clamp(e[2], 0.2f, 0.42f)),
                    hsv(m[0], Math.min(m[1], 0.7f), clamp(m[2] * 0.8f, 0.14f, 0.32f)), Color.WHITE,
                    hsv(m[0], Math.min(0.4f, m[1] * m[2]), 1)};
        }
        return new int[]{hsv(e[0], Math.min(e[1], 0.2f), clamp(e[2], 0.92f, 1)),
                hsv(m[0], Math.min(m[1], 0.3f), 0.93f), hsv(m[0], Math.min(m[1], 0.75f), 0.28f),
                hsv(m[0], Math.min(m[1], 0.7f), 0.62f)};
    }

    /**
     * 详情页正文里的点缀色（要点编号、AI 解读、展开全部）：照封面的主色（e 的 palette，见 catchup/images.py），
     * 压到浅底上看得清，鲜艳程度跟着封面（灰调的封面配偏灰的颜色，几乎没有颜色的就是灰）；没有封面的用话题的形状色，
     * 没有话题的用钴蓝。
     */
    int accent(JSONObject e) {
        JSONArray palette = e == null ? null : e.optJSONArray("palette");
        if (palette != null && palette.length() == 2) {
            float[] m = new float[3];
            Color.colorToHSV(Color.parseColor(palette.optString(1)), m);
            float chroma = m[1] < 0.06f ? 0 : m[1] * m[2];
            return night ? hsv(m[0], Math.min(0.45f, chroma * 1.8f), 1) : hsv(m[0], Math.min(0.62f, chroma * 2.4f), 0.56f);
        }
        String topic = topicOf(e);
        int[] z = topic.isEmpty() ? null : zone(topic);
        return z == null || z[0] == sheet ? pri : z[2];
    }

    /** a 往 b 走 f（0 到 1）的颜色。 */
    static int mix(int a, int b, float f) {
        return Color.argb(Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * f),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int hsv(float h, float s, float v) {
        return Color.HSVToColor(new float[]{h, s, v});
    }

    int dp(float v) {
        return Math.round(v * density);
    }

    static Typeface weight(int w) {
        return Typeface.create(Typeface.DEFAULT, w, false);
    }

    /** Roboto Flex 的某个宽度和字重；中文接系统字体。wdth 25～151，100 是标准宽度。 */
    static Typeface flex(Context c, int wdth, int wght) {
        String key = wdth + "/" + wght;
        Typeface tf = FLEX.get(key);
        if (tf != null) return tf;
        try {
            Font font = new Font.Builder(c.getResources(), R.font.roboto_flex)
                    .setFontVariationSettings("'wdth' " + wdth + ", 'wght' " + wght)
                    .setWeight(wght)
                    .build();
            tf = new Typeface.CustomFallbackBuilder(new FontFamily.Builder(font).build())
                    .setSystemFallback("sans-serif")
                    .setStyle(new FontStyle(wght, FontStyle.FONT_SLANT_UPRIGHT))
                    .build();
        } catch (IOException e) {
            tf = weight(wght);
        }
        FLEX.put(key, tf);
        return tf;
    }

    TextView text(Context c, CharSequence s, float sp, int color, int weight) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(weight(weight));
        t.setIncludeFontPadding(false);
        return t;
    }

    GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /** 四个角各自的圆角：左上、右上、右下、左下。 */
    GradientDrawable round(int color, float tl, float tr, float br, float bl) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadii(new float[]{dp(tl), dp(tl), dp(tr), dp(tr), dp(br), dp(br), dp(bl), dp(bl)});
        return d;
    }

    /** 圆角背景 + 点按水波纹。 */
    Drawable ripple(int color, float radiusDp) {
        return ripple(color, radiusDp, radiusDp, radiusDp, radiusDp);
    }

    Drawable ripple(int color, float tl, float tr, float br, float bl) {
        return new RippleDrawable(ColorStateList.valueOf(night ? 0x22FFFFFF : 0x14000000),
                round(color, tl, tr, br, bl), round(Color.WHITE, tl, tr, br, bl));
    }

    /** 从上往下渐变的圆角背景 + 点按水波纹；深底上的水波纹是浅色的。 */
    Drawable ripple(int top, int bottom, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        float r = dp(radiusDp);
        g.setCornerRadii(new float[]{r, r, r, r, r, r, r, r}); // 四个角分开给，右滑时 SwipeDismiss 才能改圆角
        return new RippleDrawable(ColorStateList.valueOf(Color.luminance(top) < 0.3f ? 0x22FFFFFF : 0x14000000),
                g, round(Color.WHITE, radiusDp));
    }

    /**
     * 分段列表里的一项：一组的第一项上面两个角是大圆角，最后一项下面两个角是大圆角，中间都是小圆角。卡片色的是磨砂，
     * 别的颜色（“有了答案”的浅钴蓝）是带同样颗粒和倒角的半透明底。
     */
    Drawable segment(int color, boolean first, boolean last) {
        float top = first ? R_XL : R_XS, bottom = last ? R_XL : R_XS;
        int fill = color == sheet ? frost : (color & 0x00FFFFFF) | 0xC7000000;
        return new RippleDrawable(ColorStateList.valueOf(night ? 0x22FFFFFF : 0x14000000),
                frost(fill, top, top, bottom, bottom), round(Color.WHITE, top, top, bottom, bottom));
    }

    /** 磨砂：半透明的底（fill，一般是 frost），带雾面颗粒和一圈细倒角（上沿亮、下沿暗）。四个角各自的圆角同 round。 */
    GradientDrawable frost(int fill, float tl, float tr, float br, float bl) {
        Surface d = new Surface(frostFinish);
        d.setColor(fill);
        d.setCornerRadii(new float[]{dp(tl), dp(tl), dp(tr), dp(tr), dp(br), dp(br), dp(bl), dp(bl)});
        return d;
    }

    GradientDrawable frost(float radiusDp) {
        return frost(frost, radiusDp, radiusDp, radiusDp, radiusDp);
    }

    /** 磨砂底 + 点按水波纹。 */
    Drawable frostRipple(int fill, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(night ? 0x22FFFFFF : 0x14000000),
                frost(fill, radiusDp, radiusDp, radiusDp, radiusDp), round(Color.WHITE, radiusDp));
    }

    /** 静止的金属小块（选中的标签、“新”“要闻”、浮动工具栏）：以 base 为中间色的斜向明暗渐变，带颗粒和倒角。 */
    GradientDrawable metal(int base, float radiusDp) {
        Surface d = new Surface(metalFinish);
        shine(d, base);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /** 把 d 涂成以 base 为中间色的金属：亮—暗—次亮—更暗，从左上斜到右下，像斜着看一块拉丝金属。 */
    static void shine(GradientDrawable d, int base) {
        d.setOrientation(GradientDrawable.Orientation.TL_BR);
        d.setColors(new int[]{mix(base, Color.WHITE, .2f), mix(base, Color.BLACK, .22f), mix(base, Color.WHITE, .1f),
                mix(base, Color.BLACK, .34f)}, new float[]{0, .4f, .58f, 1});
    }

    /** 一个圆角背景此刻的四个角（像素）：分开给的直接用，统一给的（胶囊写成 99dp）收到高度的一半。 */
    static float[] radii(GradientDrawable d) {
        float[] r;
        try {
            r = d.getCornerRadii();
        } catch (NullPointerException e) { // Android 10 上统一给圆角时这里会抛异常，而不是返回 null
            r = null;
        }
        if (r != null) return r;
        Rect b = d.getBounds();
        float v = d.getCornerRadius();
        if (b.width() > 0 && b.height() > 0) v = Math.min(v, Math.min(b.width(), b.height()) / 2f);
        return new float[]{v, v, v, v, v, v, v, v};
    }

    /** 磨砂和金属表面的质感：雾面颗粒和一圈细倒角（上沿一道细亮线、下沿一道细暗线），画在圆角形状里面。 */
    static final class Finish {
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Flow.Grain grain;
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final float density;
        private final int hi, lo;
        private int rimTop = Integer.MIN_VALUE, rimBottom;

        Finish(Context c, Flow.Grain grain, int hi, int lo) {
            this.density = c.getResources().getDisplayMetrics().density;
            this.grain = grain;
            this.hi = hi;
            this.lo = lo;
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(2 * density); // 一半在形状外面被裁掉，露出 1dp
        }

        void draw(Canvas c, Rect b, float[] radii) {
            if (b.isEmpty()) return;
            rect.set(b);
            path.reset();
            path.addRoundRect(rect, radii, Path.Direction.CW);
            int save = c.save();
            c.clipPath(path);
            grain.draw(c, b.left, b.top, b.right, b.bottom);
            if (b.top != rimTop || b.bottom != rimBottom) {
                rimTop = b.top;
                rimBottom = b.bottom;
                float edge = Math.min(.5f, 3 * density / b.height());
                rim.setShader(new LinearGradient(0, b.top, 0, b.bottom, new int[]{hi, hi & 0xFFFFFF, lo & 0xFFFFFF, lo},
                        new float[]{0, edge, 1 - edge, 1}, Shader.TileMode.CLAMP));
            }
            c.drawPath(path, rim);
            c.restoreToCount(save);
        }
    }

    /** 带质感的圆角背景：底色或渐变照 GradientDrawable 画，上面加 Finish。 */
    static final class Surface extends GradientDrawable {
        private final Finish finish;

        Surface(Finish finish) {
            this.finish = finish;
        }

        @Override
        public void draw(Canvas c) {
            super.draw(c);
            finish.draw(c, getBounds(), radii(this));
        }
    }

    /**
     * 内容变了不跳：新出现的淡入，消失的淡出，其余的平滑挪到新位置，外面的容器高度也跟着平滑变。
     * 只给原地更新内容的容器用；整块清空重建的容器别用，新旧内容会一起淡入淡出。
     */
    static void smooth(ViewGroup... groups) {
        for (ViewGroup g : groups) {
            LayoutTransition lt = new LayoutTransition();
            lt.enableTransitionType(LayoutTransition.CHANGING);
            lt.setDuration(280);
            lt.setInterpolator(LayoutTransition.CHANGE_APPEARING, new DecelerateInterpolator(1.5f));
            lt.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, new DecelerateInterpolator(1.5f));
            lt.setInterpolator(LayoutTransition.CHANGING, new DecelerateInterpolator(1.5f));
            lt.setStartDelay(LayoutTransition.APPEARING, 120); // 先让位，再淡入，两段叠着一点
            lt.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 80);
            g.setLayoutTransition(lt);
        }
    }

    /** 把一列行排成分段列表：卡片色底，相隔 3dp，头尾大圆角。 */
    void segments(LinearLayout list) {
        int n = list.getChildCount();
        for (int i = 0; i < n; i++) {
            View row = list.getChildAt(i);
            row.setBackground(segment(sheet, i == 0, i == n - 1));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
            if (lp == null) lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = i == 0 ? 0 : dp(3);
            row.setLayoutParams(lp);
        }
    }

    /** 胶囊按钮：filled 为钴蓝实心（主操作），否则为浅钴蓝（次要操作）。 */
    TextView pill(Context c, String label, boolean filled) {
        TextView b = text(c, label, 14, filled ? onPri : onPc, 700);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        press(b, filled ? pri : pc, 99);
        return b;
    }

    /**
     * 可以按的按钮：按下时圆角收到 12、整体略微缩小，松手弹回，比水波纹更直接地告诉你“按到了”。
     * 磨砂色（frostButton）的是磨砂按钮；酸橙、主色、墨色这些实色的是静止的金属；别的颜色照旧平涂。
     */
    void press(View v, int color, float radiusDp) {
        Spring.Shape shape = new Spring.Shape(color, dp(radiusDp), dp(R_S));
        if (color == frostButton || color == frost) shape.finish(frostFinish);
        else if (color == lime || color == pri || color == inv) shape.finish(metalFinish).metal(color);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(night ? 0x22FFFFFF : 0x14000000), shape, null));
        StateListAnimator scale = new StateListAnimator();
        scale.addState(new int[]{android.R.attr.state_pressed}, scaleTo(v, 0.95f));
        scale.addState(new int[0], scaleTo(v, 1f));
        v.setStateListAnimator(scale);
    }

    private static ObjectAnimator scaleTo(View v, float s) {
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(View.SCALE_X, s), PropertyValuesHolder.ofFloat(View.SCALE_Y, s));
        a.setDuration(Spring.FAST.duration);
        a.setInterpolator(Spring.FAST);
        return a;
    }

    /**
     * 浮动按钮下面垫的渐隐底：内容滚到按钮下面时逐渐淡成底色，按钮落在干净的底色上，不用阴影也分得清层次
     * （系统阴影在 vivo 上边缘整齐、像描边，看着假）。bottom 为 true 时贴底部，否则贴顶部；靠边的一半是实色 color（页面底色）。
     */
    View fade(Context c, boolean bottom, int color) {
        GradientDrawable g = new GradientDrawable(bottom ? GradientDrawable.Orientation.BOTTOM_TOP
                : GradientDrawable.Orientation.TOP_BOTTOM, null);
        g.setColors(new int[]{color, color, color & 0x00FFFFFF}, new float[]{0f, 0.55f, 1f});
        View v = new View(c);
        v.setBackground(g);
        return v;
    }

    /** 左上角圆形返回按钮。 */
    TextView backButton(Activity a) {
        TextView back = text(a, "←", 20, ink, 700);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回");
        back.setBackground(frostRipple(frostButton, 99));
        back.setOnClickListener(v -> a.onBackPressed());
        return back;
    }

    /** 底部弹出的面板：顶部圆角、拖动条，从底下升起、背景一起渐暗，按住往下拉可以收起（见 Swipe.Sheet）。content 是面板里的内容。 */
    Dialog sheet(Activity a, View content) {
        Swipe.Sheet d = new Swipe.Sheet(a);
        LinearLayout box = d.box;
        box.setPadding(0, dp(10), 0, dp(24));
        box.setBackground(round(bg, R_2XL, R_2XL, 0, 0));
        View grab = new View(a);
        grab.setBackground(round(line, 2));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(36), dp(4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.bottomMargin = dp(14);
        box.addView(grab, glp);
        box.addView(content);
        return d;
    }

    /** 内容延伸到状态栏和导航栏下面（Android 11+），由各页面自己处理 insets；老系统退回纯色系统栏。 */
    void applyWindow(Activity a) {
        Window w = a.getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            w.setNavigationBarContrastEnforced(false);
        } else {
            w.setStatusBarColor(bg);
            w.setNavigationBarColor(bg);
        }
        if (!night) {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        w.getDecorView().setBackgroundColor(bg);
    }

    /** ISO 8601 时间转毫秒，解析失败返回 0。 */
    static long millis(String iso) {
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 刚刚 / 12 分钟前 / 3 小时前 / 昨天 18:20 / 10月21日 */
    static String ago(long ms) {
        long d = System.currentTimeMillis() - ms;
        if (d < 60_000) return "刚刚";
        if (d < 3600_000) return d / 60_000 + " 分钟前";
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        long start = today.getTimeInMillis();
        if (ms >= start) return d / 3600_000 + " 小时前";
        Date date = new Date(ms);
        if (ms >= start - 86400_000L) return "昨天 " + new SimpleDateFormat("HH:mm", Locale.CHINA).format(date);
        return new SimpleDateFormat("M月d日", Locale.CHINA).format(date);
    }
}
