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
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 主界面：连接设置 + 消息列表（新的在上）。 */
public class MainActivity extends Activity {
    private final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
    private TextView status;
    private LinearLayout settings;
    private Button battery;
    private Button update;
    private Updater.Release release;
    private JSONArray messages = new JSONArray();
    private BaseAdapter adapter;

    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, 0);

        status = new TextView(this);
        status.setPadding(0, pad / 2, 0, pad / 2);
        status.setOnClickListener(v -> settings.setVisibility(
                settings.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        root.addView(status);

        update = new Button(this);
        update.setVisibility(View.GONE);
        update.setOnClickListener(v -> installUpdate());
        root.addView(update);

        settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        EditText server = new EditText(this);
        server.setHint("服务器地址");
        server.setSingleLine();
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(Store.prefs(this).getString("server", Store.DEFAULT_SERVER));
        EditText token = new EditText(this);
        token.setHint("令牌（FEED_TOKEN）");
        token.setSingleLine();
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(Store.prefs(this).getString("token", ""));
        Button save = new Button(this);
        save.setText("保存并连接");
        save.setOnClickListener(v -> {
            Store.prefs(this).edit()
                    .putString("server", server.getText().toString().trim())
                    .putString("token", token.getText().toString().trim())
                    .apply();
            FeedService.start(this);
            settings.setVisibility(View.GONE);
        });
        battery = new Button(this);
        battery.setText("允许后台常驻（关闭电池优化）");
        battery.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        Button checkUpdate = new Button(this);
        checkUpdate.setText("检查更新（当前 v" + Updater.currentVersion(this) + "）");
        checkUpdate.setOnClickListener(v -> checkUpdate(true));
        settings.addView(server);
        settings.addView(token);
        settings.addView(save);
        settings.addView(battery);
        settings.addView(checkUpdate);
        root.addView(settings);

        ListView list = new ListView(this);
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
                if (convert == null) convert = newRow(); // 复用滚出屏幕的行，避免每次滚动都新建 View
                TextView[] views = (TextView[]) convert.getTag();
                JSONObject m = getItem(i);
                views[0].setText(m.optString("title"));
                views[1].setText(fmt.format(new Date((long) (m.optDouble("time") * 1000)))
                        + "\n" + Store.plain(m.optString("message")));
                return convert;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, i, id) -> openLinks((JSONObject) adapter.getItem(i)));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        boolean configured = !Store.prefs(this).getString("token", "").isEmpty();
        settings.setVisibility(configured ? View.GONE : View.VISIBLE);
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
        IntentFilter f = new IntentFilter(FeedService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(updates, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updates, f);
        }
        refresh();
        if (Updater.due(this)) checkUpdate(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(updates);
    }

    private void refresh() {
        status.setText("● " + FeedService.status + "　（点此展开设置）");
        battery.setVisibility(getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())
                ? View.GONE : View.VISIBLE);
        messages = Store.load(this);
        adapter.notifyDataSetChanged();
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        TextView title = new TextView(this);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView body = new TextView(this);
        body.setTextSize(14);
        body.setAlpha(0.75f);
        row.addView(title);
        row.addView(body);
        row.setTag(new TextView[]{title, body});
        return row;
    }

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
            String text = msg;
            runOnUiThread(() -> {
                if (found != null) {
                    release = found;
                    update.setText("发现新版本 v" + found.version + "，点此更新");
                    update.setEnabled(true);
                    update.setVisibility(View.VISIBLE);
                } else if (manual) {
                    toast(text);
                }
            });
        }, "update-check").start();
    }

    private void installUpdate() {
        Updater.Release r = release;
        if (r == null) return;
        update.setEnabled(false);
        update.setText("正在下载 v" + r.version + "…");
        new Thread(() -> {
            try {
                Updater.install(this, r);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    toast("下载更新失败：" + e.getMessage());
                    update.setEnabled(true);
                    update.setText("发现新版本 v" + r.version + "，点此重试");
                });
            }
        }, "update-install").start();
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
            if (release != null) {
                update.setEnabled(true);
                update.setText("发现新版本 v" + release.version + "，点此重试");
            }
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

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

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
