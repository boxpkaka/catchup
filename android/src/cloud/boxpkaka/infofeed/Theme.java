package cloud.boxpkaka.infofeed;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
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

import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 视觉规范：配色沿用图标（宣纸、素笺、墨、石墨、朱砂），朱砂只用于强调（新的、最新进展、警示）；
 * 层次靠字重而不是颜色：900 字标与补课句、700 标题、500 辅助信息、400 正文。
 */
final class Theme {
    static final int CINNABAR = 0xFFFF5A36;

    final boolean night;
    final int bg, sheet, ink, sub, line, chip;
    private final float density;

    Theme(Context c) {
        night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF141416 : 0xFFF3EEE4;
        sheet = night ? 0xFF1F1F22 : 0xFFFBF8F2;
        ink = night ? 0xFFEDE7DA : 0xFF1A1A1D;
        sub = night ? 0xFF9B958A : 0xFF6E6A63;
        line = night ? 0xFF2C2B30 : 0xFFE4DDD0;
        chip = night ? 0x1FEDE7DA : 0x121A1A1D;
        density = c.getResources().getDisplayMetrics().density;
    }

    int dp(float v) {
        return Math.round(v * density);
    }

    static Typeface weight(int w) {
        return Typeface.create(Typeface.DEFAULT, w, false);
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

    /** 圆角背景 + 点按水波纹。 */
    Drawable ripple(int color, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(night ? 0x22FFFFFF : 0x14000000),
                round(color, radiusDp), round(Color.WHITE, radiusDp));
    }

    /** 胶囊按钮：filled 为墨色实心（主操作），否则为浅底。 */
    TextView pill(Context c, String label, boolean filled) {
        TextView b = text(c, label, 14, filled ? bg : ink, 700);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setBackground(ripple(filled ? ink : chip, 99));
        return b;
    }

    /** 左上角圆形返回按钮。 */
    TextView backButton(Activity a) {
        TextView back = text(a, "←", 20, ink, 700);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回");
        back.setBackground(ripple(sheet, 22));
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
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(bg);
        float r = dp(26);
        shape.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        box.setBackground(shape);
        View grab = new View(a);
        grab.setBackground(round(line, 2));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(36), dp(4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.bottomMargin = dp(10);
        box.addView(grab, glp);
        box.addView(content);
        d.setContentView(box);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        w.setGravity(Gravity.BOTTOM);
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0.32f);
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
