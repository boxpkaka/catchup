package cloud.boxpkaka.infofeed;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ReplacementSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 主界面：按天分组的消息时间索引。配色沿用图标：宣纸底、墨色文字、朱砂只用于未读点和连接异常。
 * 不需要任何设置即可使用，菜单里只有检查更新和后台运行设置。
 */
public class MainActivity extends Activity {
    private static final int CINNABAR = 0xFFFF5A36;
    private static final long DAY_MS = 86400_000L;

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.CHINA);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("M月d日", Locale.CHINA);
    private final SimpleDateFormat weekFmt = new SimpleDateFormat("EEEE", Locale.CHINA);
    private int bg, fg, sub, raise;
    private boolean night;

    private TextView status;
    private View statusDot;
    private LinearLayout updateNotice;
    private TextView updateText, updateAction;
    private LinearLayout batteryNotice;
    private TextView empty;
    private ListView list;
    private BaseAdapter adapter;
    private final List<Object> rows = new ArrayList<>(); // String 是分组标题，JSONObject 是消息
    private JSONArray messages = new JSONArray();
    private long seenAtOpen; // 本次打开前已看过的最新 id，比它新的显示未读点
    private Updater.Release release;
    private boolean receiving;

    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF161618 : 0xFFF3EEE4;
        fg = night ? 0xFFEDE7DA : 0xFF1A1A1D;
        sub = night ? 0xFF9B958A : 0xFF6E6A63;
        raise = night ? 0xFF202023 : 0xFFFBF8F2;
        Window w = getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        if (!night) {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        setContentView(buildMain());

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        FeedService.start(this);
        onInstallResult(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        onInstallResult(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        seenAtOpen = Store.seenId(this);
        if (seenAtOpen < 0) { // 第一次打开：已有的历史消息不算未读
            seenAtOpen = Store.lastId(this);
            Store.markSeen(this, seenAtOpen);
        }
        if (!receiving) {
            IntentFilter f = new IntentFilter(FeedService.ACTION_UPDATE);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(updates, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(updates, f);
            receiving = true;
        }
        refresh();
        if (Updater.due(this)) checkUpdate(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiving) {
            unregisterReceiver(updates);
            receiving = false;
        }
        Store.markSeen(this, Store.lastId(this)); // 离开时视为已看过当前全部消息
    }

    // ---------- 界面 ----------

    private View buildMain() {
        LinearLayout root = column();
        root.setBackgroundColor(bg);

        // 顶栏：字标 + 连接状态，右侧菜单
        LinearLayout top = new LinearLayout(this);
        top.setPadding(dp(20), dp(16), dp(10), dp(4));
        LinearLayout titles = column();
        titles.addView(wordmark(30));
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(6), 0, 0);
        statusDot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(6), dp(6));
        dotLp.rightMargin = dp(7);
        statusRow.addView(statusDot, dotLp);
        status = text("", 12, sub, false);
        statusRow.addView(status);
        titles.addView(statusRow);
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView menu = text("⋯", 22, fg, true);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("菜单");
        menu.setBackground(ripple(Color.TRANSPARENT, dp(22)));
        menu.setOnClickListener(v -> showMenu());
        top.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(top);

        // 新版本提示：反色横条
        updateNotice = notice(fg);
        updateText = text("", 13, bg, true);
        updateNotice.addView(updateText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        updateAction = text("更新", 12, fg, true);
        updateAction.setPadding(dp(14), dp(6), dp(14), dp(6));
        updateAction.setBackground(round(bg, dp(99)));
        updateNotice.addView(updateAction);
        updateNotice.setOnClickListener(v -> installUpdate());
        updateNotice.setVisibility(View.GONE);
        root.addView(updateNotice, noticeLp());

        // 电池优化提示
        batteryNotice = notice(raise);
        LinearLayout bt = column();
        bt.addView(text("允许后台运行", 13, fg, true));
        bt.addView(text("关闭电池优化后才能及时收到推送", 12, sub, false));
        batteryNotice.addView(bt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        batteryNotice.addView(text("去设置", 13, fg, true));
        batteryNotice.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        root.addView(batteryNotice, noticeLp());

        FrameLayout content = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setClipToPadding(false);
        list.setPadding(0, dp(6), 0, dp(28));
        list.setVerticalScrollBarEnabled(false);
        adapter = new FeedAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, i, id) -> {
            Object row = rows.get(i);
            if (row instanceof JSONObject) openLinks((JSONObject) row);
        });
        content.addView(list);
        empty = text("还没有消息\n有重要变化时会推送到这里", 14, sub, false);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(0, 1.5f);
        content.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private final class FeedAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int i) {
            return rows.get(i) instanceof String ? 0 : 1;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int i) {
            return getItemViewType(i) == 1;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Object row = rows.get(i);
            if (row instanceof String) {
                if (convert == null) convert = newDayRow();
                TextView[] v = (TextView[]) convert.getTag();
                String[] parts = ((String) row).split("\n", 2);
                v[0].setText(parts[0]);
                v[1].setText(parts[1]);
                return convert;
            }
            if (convert == null) convert = newItemRow(); // 复用滚出屏幕的行
            Object[] v = (Object[]) convert.getTag();
            JSONObject m = (JSONObject) row;
            Store.Entry e = Store.entry(m);
            boolean unread = m.optLong("id") > seenAtOpen;
            TextView when = (TextView) v[0];
            when.setText(timeFmt.format(new Date(millis(m))));
            when.setTextColor(unread ? fg : sub);
            when.setTypeface(unread ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            ((View) v[1]).setVisibility(unread ? View.VISIBLE : View.INVISIBLE);
            show((TextView) v[2], e.topic);
            ((TextView) v[3]).setText(e.headline);
            show((TextView) v[4], e.snippet);
            String meta = e.count > 1 ? (e.source.isEmpty() ? "" : e.source + "，") + "共 " + e.count + " 条" : e.source;
            show((TextView) v[5], meta);
            return convert;
        }
    }

    private View newDayRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.BOTTOM);
        row.setPadding(dp(20), dp(18), dp(20), dp(6));
        TextView label = text("", 13, fg, true);
        TextView date = text("", 12, sub, false);
        date.setPadding(dp(8), 0, 0, 0);
        row.addView(label);
        row.addView(date);
        row.setTag(new TextView[]{label, date});
        return row;
    }

    private View newItemRow() {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(10), dp(20), dp(12));
        row.setBackground(ripple(Color.TRANSPARENT, 0));

        FrameLayout gutter = new FrameLayout(this);
        TextView when = text("", 12, sub, false);
        when.setGravity(Gravity.END);
        when.setFontFeatureSettings("tnum");
        when.setPadding(0, dp(1), dp(12), 0);
        gutter.addView(when, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        View dot = new View(this);
        dot.setBackground(round(CINNABAR, dp(4)));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(7), dp(7));
        dlp.leftMargin = dp(10);
        dlp.topMargin = dp(7);
        gutter.addView(dot, dlp);
        row.addView(gutter, new LinearLayout.LayoutParams(dp(68), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout col = column();
        TextView topic = text("", 11, sub, false);
        TextView headline = text("", 15, fg, true);
        headline.setLineSpacing(0, 1.2f);
        headline.setMaxLines(3);
        headline.setEllipsize(TextUtils.TruncateAt.END);
        TextView snippet = text("", 13, sub, false);
        snippet.setLineSpacing(0, 1.3f);
        snippet.setMaxLines(2);
        snippet.setEllipsize(TextUtils.TruncateAt.END);
        snippet.setPadding(0, dp(3), 0, 0);
        TextView meta = text("", 12, fg, false);
        meta.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        meta.setPadding(0, dp(5), 0, 0);
        col.addView(topic);
        col.addView(headline);
        col.addView(snippet);
        col.addView(meta);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setTag(new Object[]{when, dot, topic, headline, snippet, meta});
        return row;
    }

    private void refresh() {
        String s = FeedService.status;
        boolean ok = s.startsWith("已连接");
        boolean bad = s.startsWith("连接断开");
        JSONArray latest = Store.load(this);
        boolean grew = latest.length() > messages.length();
        messages = latest;

        long today = startOfToday();
        int todayCount = 0;
        rows.clear();
        String lastDay = null;
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            long t = millis(m);
            if (t >= today) todayCount++;
            String day = dayLabel(t, today);
            if (!day.equals(lastDay)) {
                rows.add(day);
                lastDay = day;
            }
            rows.add(m);
        }

        status.setText(ok && todayCount > 0 ? "已连接，今天 " + todayCount + " 条" : s);
        status.setTextColor(bad ? CINNABAR : sub);
        statusDot.setBackground(round(bad ? CINNABAR : ok ? fg : sub, dp(3)));
        statusDot.setAlpha(bad ? 1f : 0.55f);
        batteryNotice.setVisibility(getSystemService(PowerManager.class)
                .isIgnoringBatteryOptimizations(getPackageName()) ? View.GONE : View.VISIBLE);
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
        if (grew && list.getFirstVisiblePosition() == 0) list.setSelection(0); // 新消息在顶部
    }

    /** 分组标题，"标签\n补充"：今天 10月24日 / 昨天 10月23日 / 10月21日 星期二 */
    private String dayLabel(long t, long today) {
        Date d = new Date(t);
        if (t >= today) return "今天\n" + dateFmt.format(d);
        if (t >= today - DAY_MS) return "昨天\n" + dateFmt.format(d);
        return dateFmt.format(d) + "\n" + weekFmt.format(d);
    }

    // ---------- 菜单 ----------

    private void showMenu() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = column();
        sheet.setPadding(0, dp(10), 0, dp(18));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(bg);
        float r = dp(24);
        shape.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(shape);
        View grab = new View(this);
        grab.setBackground(round(night ? 0xFF3A393E : 0xFFE2DCCF, dp(2)));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(36), dp(4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.bottomMargin = dp(8);
        sheet.addView(grab, glp);
        sheet.addView(menuRow("检查更新", "当前 " + Updater.currentVersion(this), () -> {
            d.dismiss();
            checkUpdate(true);
        }));
        sheet.addView(menuRow("后台运行设置", "", () -> {
            d.dismiss();
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }));
        d.setContentView(sheet);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setGravity(Gravity.BOTTOM);
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0.35f);
        d.show();
    }

    private View menuRow(String label, String detail, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(22), dp(15), dp(22), dp(15));
        row.setBackground(ripple(Color.TRANSPARENT, 0));
        row.addView(text(label, 15, fg, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!detail.isEmpty()) row.addView(text(detail, 13, sub, false));
        row.setOnClickListener(v -> action.run());
        return row;
    }

    // ---------- 应用内更新 ----------

    /** 后台检查新版本；manual 为 true 时把“已是最新”或失败原因也提示出来。 */
    private void checkUpdate(boolean manual) {
        new Thread(() -> {
            String msg;
            Updater.Release r = null;
            try {
                r = Updater.check(this);
                msg = r == null ? "已是最新版本" : null;
            } catch (Exception e) {
                msg = "检查更新失败：" + e.getMessage();
            }
            Updater.Release found = r;
            String toastText = msg;
            runOnUiThread(() -> {
                if (found != null) {
                    release = found;
                    showUpdate("新版本 " + found.version + " 已就绪", true);
                } else if (manual) {
                    toast(toastText);
                }
            });
        }, "update-check").start();
    }

    private void installUpdate() {
        Updater.Release r = release;
        if (r == null || updateAction.getVisibility() != View.VISIBLE) return;
        showUpdate("正在下载 " + r.version + "…", false);
        new Thread(() -> {
            try {
                Updater.install(this, r);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    toast("下载更新失败：" + e.getMessage());
                    showUpdate("新版本 " + r.version + " 下载失败", true);
                });
            }
        }, "update-install").start();
    }

    private void showUpdate(String s, boolean actionable) {
        updateText.setText(s);
        updateAction.setText(actionable && s.contains("失败") ? "重试" : "更新");
        updateAction.setVisibility(actionable ? View.VISIBLE : View.GONE);
        updateNotice.setVisibility(View.VISIBLE);
    }

    /** PackageInstaller 的回调：需要用户确认时弹出系统安装界面，失败时提示原因。 */
    private void onInstallResult(Intent intent) {
        if (intent == null || !Updater.ACTION_INSTALLED.equals(intent.getAction())) return;
        int code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) startActivity(confirm);
        } else if (code != PackageInstaller.STATUS_SUCCESS) {
            toast("安装失败：" + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            if (release != null) showUpdate("新版本 " + release.version + " 安装失败", true);
        }
    }

    // ---------- 打开链接 ----------

    private void openLinks(JSONObject m) {
        List<String[]> links = Store.links(m);
        if (links.isEmpty()) return;
        if (links.size() == 1) {
            open(links.get(0)[1]);
            return;
        }
        String[] titles = new String[links.size()];
        for (int i = 0; i < titles.length; i++) titles[i] = links.get(i)[0];
        new AlertDialog.Builder(this)
                .setTitle(m.optString("title"))
                .setItems(titles, (d, which) -> open(links.get(which)[1]))
                .show();
    }

    private void open(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    // ---------- 小工具 ----------

    /** 字标：小写粗体 catchup，末尾一个落在基线上的朱砂圆点，与图标里被 C 接住的点呼应。 */
    private TextView wordmark(int sp) {
        TextView t = text("", sp, fg, false);
        t.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        t.setLetterSpacing(-0.035f);
        t.setIncludeFontPadding(false);
        SpannableString s = new SpannableString("catchup.");
        s.setSpan(new DotSpan(CINNABAR), 7, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setText(s);
        return t;
    }

    private static final class DotSpan extends ReplacementSpan {
        private final int color;

        DotSpan(int color) {
            this.color = color;
        }

        @Override
        public int getSize(Paint p, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return Math.round(p.getTextSize() * 0.26f);
        }

        @Override
        public void draw(Canvas c, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint p) {
            float r = p.getTextSize() * 0.095f;
            int old = p.getColor();
            p.setColor(color);
            c.drawCircle(x + p.getTextSize() * 0.06f + r, y - r, r, p);
            p.setColor(old);
        }
    }

    private static long millis(JSONObject m) {
        return (long) (m.optDouble("time") * 1000);
    }

    private static long startOfToday() {
        Calendar day = Calendar.getInstance();
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        return day.getTimeInMillis();
    }

    private static void show(TextView t, String s) {
        t.setText(s);
        t.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout notice(int color) {
        LinearLayout n = new LinearLayout(this);
        n.setGravity(Gravity.CENTER_VERTICAL);
        n.setPadding(dp(14), dp(12), dp(14), dp(12));
        n.setBackground(ripple(color, dp(14)));
        return n;
    }

    private LinearLayout.LayoutParams noticeLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(20), dp(10), dp(20), 0);
        return lp;
    }

    private static GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** 圆角背景 + 点按水波纹。 */
    private Drawable ripple(int color, int radius) {
        GradientDrawable mask = round(Color.WHITE, radius);
        return new RippleDrawable(ColorStateList.valueOf(night ? 0x22FFFFFF : 0x14000000), round(color, radius), mask);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
