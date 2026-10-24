package cloud.boxpkaka.infofeed;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
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
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 主界面：首次只需输入一次令牌；之后是消息卡片列表，设置收在右上角菜单里。 */
public class MainActivity extends Activity {
    private static final int ACCENT = 0xFF2563EB;

    private final SimpleDateFormat dayFmt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.CHINA);
    private int bg, card, text, sub;
    private boolean night;

    private TextView status;
    private View dot;
    private TextView updateBanner;
    private LinearLayout batteryBanner;
    private TextView empty;
    private ListView list;
    private JSONArray messages = new JSONArray();
    private BaseAdapter adapter;
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
        bg = night ? 0xFF0F1115 : 0xFFF4F5F7;
        card = night ? 0xFF1B1E24 : 0xFFFFFFFF;
        text = night ? 0xFFE8EAED : 0xFF16181D;
        sub = night ? 0xFF9AA0A6 : 0xFF6B7280;
        Window w = getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        if (!night) {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        if (token().isEmpty()) showWelcome();
        else showMain();
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
        if (list != null) onShown(); // 还在欢迎页时什么都不做
    }

    private void onShown() {
        startReceiving();
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
    }

    // ---------- 欢迎页：只输入一次令牌 ----------

    private void showWelcome() {
        LinearLayout root = column();
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(28), dp(24), dp(28), dp(24));
        root.setBackgroundColor(bg);

        View logo = new View(this);
        logo.setBackground(getDrawable(R.drawable.ic_launcher));
        root.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));

        TextView title = label("信息流", 30, text, true);
        title.setPadding(0, dp(20), 0, dp(8));
        root.addView(title);
        TextView intro = label("AI 领域真正重要的变化，第一时间推送到你的手机。", 16, sub, false);
        intro.setLineSpacing(0, 1.3f);
        root.addView(intro);

        EditText token = input("输入访问令牌");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(36);
        root.addView(token, lp);

        TextView start = button("开始使用");
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        bp.topMargin = dp(14);
        root.addView(start, bp);

        Runnable submit = () -> {
            String t = token.getText().toString().trim();
            if (t.isEmpty()) {
                token.setError("请输入令牌");
                return;
            }
            saveToken(t);
            showMain();
            onShown();
        };
        start.setOnClickListener(v -> submit.run());
        token.setOnEditorActionListener((v, id, e) -> {
            submit.run();
            return true;
        });
        setContentView(root);
    }

    // ---------- 主界面 ----------

    private void showMain() {
        LinearLayout root = column();
        root.setBackgroundColor(bg);

        // 顶栏：标题 + 连接状态，右侧是菜单
        FrameLayout header = new FrameLayout(this);
        header.setPadding(dp(20), dp(18), dp(8), dp(10));
        LinearLayout titles = column();
        titles.addView(label("信息流", 26, text, true));
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(4), 0, 0);
        dot = new View(this);
        statusRow.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        status = label("", 13, sub, false);
        status.setPadding(dp(6), 0, 0, 0);
        status.setSingleLine();
        status.setEllipsize(TextUtils.TruncateAt.END);
        statusRow.addView(status);
        statusRow.setOnClickListener(v -> {
            if (FeedService.status.startsWith("令牌错误")) editToken();
        });
        titles.addView(statusRow);
        header.addView(titles, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView menu = label("⋮", 24, text, false);
        menu.setGravity(Gravity.CENTER);
        menu.setBackground(ripple(0, dp(24)));
        menu.setOnClickListener(v -> showMenu());
        header.addView(menu, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL));
        root.addView(header);

        updateBanner = label("", 15, Color.WHITE, true);
        updateBanner.setPadding(dp(16), dp(14), dp(16), dp(14));
        updateBanner.setBackground(ripple(ACCENT, dp(14)));
        updateBanner.setVisibility(View.GONE);
        updateBanner.setOnClickListener(v -> installUpdate());
        root.addView(updateBanner, banner());

        batteryBanner = column();
        batteryBanner.setPadding(dp(16), dp(14), dp(16), dp(14));
        batteryBanner.setBackground(ripple(card, dp(14)));
        batteryBanner.addView(label("允许后台运行", 15, text, true));
        TextView hint = label("关闭电池优化后才能及时收到推送，点此设置", 13, sub, false);
        hint.setPadding(0, dp(2), 0, 0);
        batteryBanner.addView(hint);
        batteryBanner.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        root.addView(batteryBanner, banner());

        FrameLayout content = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(10));
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setClipToPadding(false);
        list.setPadding(dp(14), dp(6), dp(14), dp(24));
        list.setVerticalScrollBarEnabled(false);
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return messages.length();
            }

            @Override
            public JSONObject getItem(int i) {
                return messages.optJSONObject(messages.length() - 1 - i);
            }

            @Override
            public long getItemId(int i) {
                return getItem(i).optLong("id");
            }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                if (convert == null) convert = newCard(); // 复用滚出屏幕的卡片，避免每次滚动都新建 View
                TextView[] v = (TextView[]) convert.getTag();
                JSONObject m = getItem(i);
                v[0].setText(m.optString("title"));
                v[1].setText(Store.plain(m.optString("message")).trim());
                int n = Store.links(m).size();
                v[2].setText(when((long) (m.optDouble("time") * 1000)) + (n > 1 ? " · " + n + " 个链接" : ""));
                return convert;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, i, id) -> openLinks((JSONObject) adapter.getItem(i)));
        content.addView(list);
        empty = label("还没有消息\n有新内容时会推送到这里", 15, sub, false);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(0, 1.4f);
        content.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        FeedService.start(this);
    }

    private View newCard() {
        LinearLayout c = column();
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(ripple(card, dp(16)));
        TextView title = label("", 16, text, true);
        title.setLineSpacing(0, 1.15f);
        TextView body = label("", 14, text, false);
        body.setAlpha(0.8f);
        body.setLineSpacing(0, 1.3f);
        body.setMaxLines(6);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setPadding(0, dp(6), 0, 0);
        TextView meta = label("", 12, sub, false);
        meta.setPadding(0, dp(10), 0, 0);
        c.addView(title);
        c.addView(body);
        c.addView(meta);
        c.setTag(new TextView[]{title, body, meta});
        return c;
    }

    private void refresh() {
        if (list == null) return;
        String s = FeedService.status;
        status.setText(s.startsWith("令牌错误") ? "令牌错误，点此重新输入" : s);
        int color = s.startsWith("已连接") ? 0xFF22C55E
                : s.startsWith("连接中") ? 0xFFF59E0B
                : s.startsWith("令牌错误") || s.startsWith("已断开") ? 0xFFEF4444 : sub;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        dot.setBackground(d);
        batteryBanner.setVisibility(getSystemService(PowerManager.class)
                .isIgnoringBatteryOptimizations(getPackageName()) ? View.GONE : View.VISIBLE);
        messages = Store.load(this);
        empty.setVisibility(messages.length() == 0 ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void startReceiving() {
        if (receiving) return;
        IntentFilter f = new IntentFilter(FeedService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(updates, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updates, f);
        }
        receiving = true;
    }

    // ---------- 菜单与设置 ----------

    private void showMenu() {
        String[] items = {"检查更新（当前 v" + Updater.currentVersion(this) + "）", "修改令牌", "后台运行设置"};
        new AlertDialog.Builder(this).setItems(items, (d, which) -> {
            if (which == 0) checkUpdate(true);
            else if (which == 1) editToken();
            else startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }).show();
    }

    private void editToken() {
        EditText token = input("访问令牌");
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(12), dp(20), 0);
        box.addView(token, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        new AlertDialog.Builder(this)
                .setTitle("修改令牌")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    String t = token.getText().toString().trim();
                    if (!t.isEmpty()) saveToken(t);
                })
                .show();
    }

    private String token() {
        return Store.prefs(this).getString("token", "");
    }

    private void saveToken(String t) {
        Store.prefs(this).edit().putString("token", t).apply();
        FeedService.start(this); // 服务已在运行时会用新令牌立即重连
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
                    showUpdate("发现新版本 v" + found.version + "，点击更新", true);
                } else if (manual) {
                    toast(toastText);
                }
            });
        }, "update-check").start();
    }

    private void installUpdate() {
        Updater.Release r = release;
        if (r == null || !updateBanner.isEnabled()) return;
        showUpdate("正在下载 v" + r.version + "…", false);
        new Thread(() -> {
            try {
                Updater.install(this, r);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    toast("下载更新失败：" + e.getMessage());
                    showUpdate("发现新版本 v" + r.version + "，点击重试", true);
                });
            }
        }, "update-install").start();
    }

    private void showUpdate(String s, boolean enabled) {
        if (updateBanner == null) return;
        updateBanner.setText(s);
        updateBanner.setEnabled(enabled);
        updateBanner.setVisibility(View.VISIBLE);
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
            if (release != null) showUpdate("发现新版本 v" + release.version + "，点击重试", true);
        }
    }

    // ---------- 打开链接 ----------

    private void openLinks(JSONObject m) {
        if (m == null) return;
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

    /** 刚刚 / N 分钟前 / 今天 14:20 / 昨天 09:10 / 10-23 18:00 */
    private String when(long ms) {
        long ago = System.currentTimeMillis() - ms;
        if (ago < 60_000) return "刚刚";
        if (ago < 3600_000) return ago / 60_000 + " 分钟前";
        Calendar day = Calendar.getInstance();
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        long today = day.getTimeInMillis();
        Date d = new Date(ms);
        if (ms >= today) return "今天 " + timeFmt.format(d);
        if (ms >= today - 86400_000L) return "昨天 " + timeFmt.format(d);
        return dayFmt.format(d);
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView label(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String s) {
        TextView b = label(s, 16, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(ACCENT, dp(14)));
        return b;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine();
        e.setTextSize(16);
        e.setTextColor(text);
        e.setHintTextColor(sub);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable d = new GradientDrawable();
        d.setColor(card);
        d.setCornerRadius(dp(14));
        d.setStroke(dp(1), night ? 0xFF2C3038 : 0xFFE3E5E8);
        e.setBackground(d);
        return e;
    }

    /** 圆角背景 + 点按水波纹；color 为 0 时只有水波纹。 */
    private Drawable ripple(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color == 0 ? Color.TRANSPARENT : color);
        shape.setCornerRadius(radius);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(night ? 0x33FFFFFF : 0x1F000000), shape, mask);
    }

    private LinearLayout.LayoutParams banner() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(14), dp(4), dp(14), dp(8));
        return lp;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
