package cloud.boxpkaka.infofeed;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
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
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

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
 * 视觉规范（律动）：钴蓝是主色，表示新的、有变化的；酸橙只给“问一问”和发送；墨色反白给“要闻”和选中的标签；
 * 事件不按话题上色，话题写成文字；专题页的几张卡片才有专题色（zone），帮你认路。颜色一律按用途取，深浅色各一套。
 * 层次靠字重而不是颜色：900 大标题、800 卡片标题、700 列表、400 正文。数字、时间和字标用 Roboto Flex，
 * 加宽的放大数字，收窄的放来源和时间。
 */
final class Theme {
    /** 圆角档位（dp）：分段列表内侧、按下、选中、问一问、分段列表外侧、卡片和面板。 */
    static final float R_XS = 8, R_S = 12, R_M = 16, R_L = 22, R_XL = 28, R_2XL = 32;

    private static final Map<String, Typeface> FLEX = new HashMap<>();

    final boolean night;
    final int bg, sheet, hi, ink, sub, line, chip;
    final int pri, onPri, pc, onPc, lime, onLime, inv, onInv, invPri, err, onErr;
    private final float density;

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
        err = night ? 0xFF6E1F14 : 0xFFFFDAD5; // 左滑“不需要”、连接断开
        onErr = night ? 0xFFFFDAD5 : 0xFF5C0C02;
        density = c.getResources().getDisplayMetrics().density;
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
     * 专题的颜色 {底色, 文字色, 形状色}，按专题的 key（兴趣名或榜单来源名）。只用在专题页的卡片和专题标题栏上，
     * 信息流和列表行里不出现；避开了钴蓝（新）、酸橙（问一问）和红色（不需要）。没登记的专题用卡片色。
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
            default:
                return new int[]{sheet, ink, sub};
        }
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

    /** 分段列表里的一项：一组的第一项上面两个角是大圆角，最后一项下面两个角是大圆角，中间都是小圆角。 */
    Drawable segment(int color, boolean first, boolean last) {
        float top = first ? R_XL : R_XS, bottom = last ? R_XL : R_XS;
        return ripple(color, top, top, bottom, bottom);
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

    /** 可以按的按钮：按下时圆角收到 12、整体略微缩小，松手弹回，比水波纹更直接地告诉你“按到了”。 */
    void press(View v, int color, float radiusDp) {
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(night ? 0x22FFFFFF : 0x14000000),
                new Spring.Shape(color, dp(radiusDp), dp(R_S)), null));
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
     * （系统阴影在 vivo 上边缘整齐、像描边，看着假）。bottom 为 true 时贴底部，否则贴顶部；靠边的一半是实色。
     */
    View fade(Context c, boolean bottom) {
        GradientDrawable g = new GradientDrawable(bottom ? GradientDrawable.Orientation.BOTTOM_TOP
                : GradientDrawable.Orientation.TOP_BOTTOM, null);
        g.setColors(new int[]{bg, bg, bg & 0x00FFFFFF}, new float[]{0f, 0.55f, 1f});
        View v = new View(c);
        v.setBackground(g);
        return v;
    }

    /** 左上角圆形返回按钮。 */
    TextView backButton(Activity a) {
        TextView back = text(a, "←", 20, ink, 700);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回");
        back.setBackground(ripple(sheet, 99));
        back.setOnClickListener(v -> a.onBackPressed());
        return back;
    }

    /** 底部弹出的面板：顶部圆角、拖动条、背景压暗。content 是面板里的内容。 */
    Dialog sheet(Activity a, View content) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(24));
        box.setBackground(round(bg, R_2XL, R_2XL, 0, 0));
        View grab = new View(a);
        grab.setBackground(round(line, 2));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(36), dp(4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.bottomMargin = dp(14);
        box.addView(grab, glp);
        box.addView(content);
        d.setContentView(box);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        w.setGravity(Gravity.BOTTOM);
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0.42f);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
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
