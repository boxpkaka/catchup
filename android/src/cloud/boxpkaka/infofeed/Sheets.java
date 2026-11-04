package cloud.boxpkaka.infofeed;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 首页弹出来的面板：右上角的菜单（记忆、添加链接、不需要的内容、检查更新、诊断信息、后台运行设置），菜单里的
 * “不需要的内容”（可以恢复），信息流里的“未追踪信息”（可以点“想看这类”），以及诊断信息。
 * 面板里的操作改了首页该显示什么时，交给首页重新取（见 Host）。
 */
final class Sheets {
    interface Host {
        /** 记忆变了：首页马上重新取，按新记忆重新排。 */
        void reload();

        /** 恢复了 id 这件事：首页不再藏它，并马上重新取。 */
        void restored(long id);

        /** 菜单里点了“检查更新”。 */
        void checkUpdate();
    }

    private final Activity a;
    private final Theme t;
    private final Host host;

    /** a 是首页：未追踪信息点开的详情页按 MainActivity.REQ_EVENT 回到它。 */
    Sheets(Activity a, Theme t, Host host) {
        this.a = a;
        this.t = t;
        this.host = host;
    }

    void showMenu() {
        LinearLayout sheet = new LinearLayout(a);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(t.dp(12), 0, t.dp(12), 0);
        Dialog d = t.sheet(a, sheet);
        Drawable memory = new Shapes.Blob(Shapes.SOFT, t.onPc);
        Drawable battery = a.getDrawable(R.drawable.ic_battery);
        battery.setTint(t.onPc);
        sheet.addView(menuRow(memory, "记忆", "Catchup 记得的关于你的事", () -> {
            d.dismiss();
            Push.open(a, new Intent(a, MemoryActivity.class));
        }));
        sheet.addView(menuRow("+", "添加链接", "从剪贴板", () -> {
            d.dismiss();
            addLinkFromClipboard();
        }));
        sheet.addView(menuRow("↺", "不需要的内容", "可以恢复", () -> {
            d.dismiss();
            showDismissed();
        }));
        sheet.addView(menuRow("↓", "检查更新", "当前 " + Updater.currentVersion(a), () -> {
            d.dismiss();
            host.checkUpdate();
        }));
        sheet.addView(menuRow("i", "诊断信息", "", () -> {
            d.dismiss();
            showDiagnostics();
        }));
        sheet.addView(menuRow(battery, "后台运行设置", "", () -> {
            d.dismiss();
            a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }));
        t.segments(sheet);
        d.show();
    }

    /** 标记过不需要的事件，点“恢复”后重新出现在首页。 */
    private void showDismissed() {
        new Thread(() -> {
            JSONArray list = null;
            try {
                list = Api.get(a, "/v1/dismissed").optJSONArray("events");
            } catch (Exception e) {
                Trace.log(a, "dismissed.fail", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            JSONArray got = list;
            a.runOnUiThread(() -> {
                if (a.isDestroyed()) return;
                if (got == null) {
                    toast("没有连上服务器，稍后再试");
                    return;
                }
                LinearLayout items = new LinearLayout(a);
                items.setOrientation(LinearLayout.VERTICAL);
                for (int i = 0; i < got.length(); i++) items.addView(dismissedRow(got.optJSONObject(i)));
                t.segments(items);
                listSheet("不需要的内容", got.length() == 0 ? "最近 30 天没有标记为不需要的内容"
                        : "恢复后会重新出现在首页", items).show();
            });
        }, "dismissed").start();
    }

    /** 底部面板：标题、说明和一个长列表，列表最高占屏幕 3/5，其余滚动。 */
    private Dialog listSheet(String heading, String note, View items) {
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView title = t.text(a, heading, 22, t.ink, 800);
        title.setPadding(t.dp(24), t.dp(6), t.dp(24), t.dp(4));
        content.addView(title);
        TextView sub = t.text(a, note, 13, t.sub, 400);
        sub.setLineSpacing(t.dp(4), 1f);
        sub.setPadding(t.dp(24), t.dp(4), t.dp(24), t.dp(14));
        content.addView(sub);
        int maxHeight = a.getResources().getDisplayMetrics().heightPixels * 3 / 5;
        ScrollView scroll = new ScrollView(a) {
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(t.dp(12), 0, t.dp(12), 0);
        scroll.addView(items);
        content.addView(scroll);
        return t.sheet(a, content);
    }

    /**
     * 未追踪信息（feed 的 skipped）：热度高但和你无关的事。点开看详情；点“想看这类”当场记成一条长期关注，
     * 以后同类的会排到前面。
     */
    void showSkipped(JSONArray list) {
        if (list == null || list.length() == 0) return;
        LinearLayout items = new LinearLayout(a);
        items.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < list.length(); i++) items.addView(skippedRow(list.optJSONObject(i)));
        t.segments(items);
        listSheet("未追踪信息", "这些不会推送。觉得该看的，点“想看这类”。", items).show();
        Trace.log(a, "ui.skipped", "n=" + list.length());
    }

    private View skippedRow(JSONObject s) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(t.dp(16), t.dp(14), t.dp(16), t.dp(14));
        LinearLayout text = new LinearLayout(a);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView headline = t.text(a, s.optString("headline"), 14.5f, t.ink, 600);
        headline.setLineSpacing(t.dp(3), 1f);
        headline.setMaxLines(2);
        headline.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(headline);
        TextView meta = t.text(a, s.optInt("outlets") + " 家媒体报道 · " + s.optString("why"), 12, t.sub, 400);
        meta.setPadding(0, t.dp(4), 0, 0);
        text.addView(meta);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView want = t.pill(a, "想看这类", false);
        want.setTextSize(13);
        want.setTypeface(Theme.weight(800));
        want.setPadding(t.dp(13), t.dp(8), t.dp(13), t.dp(8));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.leftMargin = t.dp(12);
        row.addView(want, wl);
        long id = s.optLong("id");
        row.setOnClickListener(v -> Card.open(a, v, new Intent(a, EventActivity.class)
                .putExtra(EventActivity.EXTRA_ID, id).putExtra(EventActivity.EXTRA_FROM, "skipped"),
                MainActivity.REQ_EVENT));
        want.setOnClickListener(v -> {
            want.setEnabled(false);
            want.setText("记下中…");
            Feedback.want(a, id, got -> {
                if (got == null) {
                    want.setEnabled(true);
                    want.setText("想看这类");
                    toast("没有记下，请重试");
                    return;
                }
                done(want, "✓ 已记下");
                String said = Feedback.remembered(got);
                if (said != null) {
                    meta.setText(said);
                    meta.setTextColor(t.ink);
                }
                host.reload();
            });
            Trace.log(a, "ui.want", "id=" + id);
        });
        return row;
    }

    /** 微信等不支持系统分享的 App：复制链接后从菜单添加。 */
    private void addLinkFromClipboard() {
        android.content.ClipData clip = a.getSystemService(android.content.ClipboardManager.class).getPrimaryClip();
        CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(a) : null;
        if (text == null || !text.toString().contains("http")) {
            toast("先在别的 App 里复制一条链接");
            return;
        }
        Push.open(a, new Intent(a, ShareActivity.class).putExtra(Intent.EXTRA_TEXT, text.toString()));
    }

    private View dismissedRow(JSONObject e) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(t.dp(16), t.dp(14), t.dp(16), t.dp(14));
        LinearLayout text = new LinearLayout(a);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView headline = t.text(a, e.optString("headline"), 14.5f, t.ink, 600);
        headline.setLineSpacing(t.dp(3), 1f);
        headline.setMaxLines(2);
        headline.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(headline);
        String reason = e.optString("reason");
        TextView when = t.text(a, "标记于 " + Theme.ago(Theme.millis(e.optString("at")))
                + (reason.isEmpty() ? "" : " · " + reason), 12, t.sub, 400);
        when.setPadding(0, t.dp(4), 0, 0);
        text.addView(when);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView restore = t.pill(a, "恢复", false);
        restore.setTextSize(13);
        restore.setTypeface(Theme.weight(800));
        restore.setPadding(t.dp(13), t.dp(8), t.dp(13), t.dp(8));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.leftMargin = t.dp(12);
        row.addView(restore, rl);
        long id = e.optLong("id");
        restore.setOnClickListener(v -> {
            restore.setEnabled(false);
            restore.setText("恢复中");
            Feedback.clear(a, id, got -> {
                if (got == null) {
                    restore.setEnabled(true);
                    restore.setText("恢复");
                    toast("恢复失败，请重试");
                    return;
                }
                done(restore, "✓ 已恢复");
                host.restored(id);
            });
            Trace.log(a, "ui.restore", "id=" + id);
        });
        return row;
    }

    /** 菜单的一行：主色容器的小方块里放图标（一个字符或一个图形），右边是名称和说明。 */
    private View menuRow(Object icon, String label, String detail, Runnable action) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(t.dp(16), t.dp(12), t.dp(16), t.dp(12));
        TextView ic = t.text(a, icon instanceof String ? (String) icon : "", 18, t.onPc, 800);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(t.round(t.pc, 14));
        if (icon instanceof Drawable) {
            Drawable d = (Drawable) icon;
            d.setBounds(0, 0, t.dp(20), t.dp(20));
            ic.setCompoundDrawablesRelative(d, null, null, null);
            ic.setPadding(t.dp(10), 0, 0, 0);
        }
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(t.dp(40), t.dp(40));
        il.rightMargin = t.dp(14);
        row.addView(ic, il);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(t.text(a, label, 15, t.ink, 700));
        if (!detail.isEmpty()) {
            TextView d = t.text(a, detail, 12.5f, t.sub, 400);
            d.setPadding(0, t.dp(2), 0, 0);
            col.addView(d);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(v -> action.run());
        return row;
    }

    /** 操作完成的按钮：从胶囊变成方一点的墨色块。 */
    private void done(TextView button, String label) {
        button.setText(label);
        button.setTextColor(t.bg);
        button.setBackground(t.round(t.ink, Theme.R_S));
    }

    /** 显示本机最近的埋点日志，可复制；不依赖网络。 */
    private void showDiagnostics() {
        String text = "v" + Updater.currentVersion(a) + " Android " + Build.VERSION.RELEASE + " " + Build.MODEL
                + "\n设备 " + Trace.device(a) + "  网络 " + Trace.network(a)
                + "\n状态 " + FeedService.status + "\n\n" + Trace.tail(a, 6000);
        TextView body = t.text(a, text, 11, t.ink, 400);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(t.dp(20), t.dp(12), t.dp(20), t.dp(12));
        ScrollView scroll = new ScrollView(a);
        scroll.addView(body);
        new AlertDialog.Builder(a)
                .setTitle("诊断信息")
                .setView(scroll)
                .setPositiveButton("复制", (dlg, w) -> {
                    a.getSystemService(android.content.ClipboardManager.class)
                            .setPrimaryClip(android.content.ClipData.newPlainText("Catchup 诊断信息", text));
                    toast("已复制");
                })
                .setNegativeButton("关闭", null)
                .show();
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void toast(String s) {
        Toast.makeText(a, s, Toast.LENGTH_LONG).show();
    }
}
