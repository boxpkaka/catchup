package cloud.boxpkaka.infofeed;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分享到 Catchup：从别的 App 用系统分享发来的链接（或首页菜单里从剪贴板添加的），
 * 交给服务器读原文、归入事件、写简报，读完直接打开这件事的详情。
 */
public class ShareActivity extends Activity {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'，。；！？、）】」]+");

    private Theme t;
    private TextView title, link, detail, retry;
    private Shapes.Loader loader;
    private View loaderBox;
    private String text, subject;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        t = new Theme(this);
        t.applyWindow(this);
        text = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        subject = getIntent().getStringExtra(Intent.EXTRA_SUBJECT);
        setContentView(build());
        submit();
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(t.bg);
        root.setPadding(t.dp(14), t.dp(14), t.dp(22), t.dp(24));
        TextView close = t.backButton(this);
        root.addView(close, new LinearLayout.LayoutParams(t.dp(44), t.dp(44)));

        // 中间一个大的加载指示器、正在读的链接和一句说明
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(t.dp(14), 0, 0, t.dp(80));
        FrameLayout box = new FrameLayout(this);
        box.setBackground(t.round(t.pc, 99));
        loader = new Shapes.Loader(this, t.pri);
        box.addView(loader, new FrameLayout.LayoutParams(t.dp(58), t.dp(58), Gravity.CENTER));
        loaderBox = box;
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(t.dp(96), t.dp(96));
        bl.bottomMargin = t.dp(28);
        body.addView(box, bl);
        body.addView(t.text(this, "分享到 Catchup", 13, t.sub, 700));
        title = t.text(this, "", 26, t.ink, 900);
        title.setLineSpacing(t.dp(6), 1f);
        title.setPadding(0, t.dp(10), 0, 0);
        body.addView(title);
        link = t.text(this, "", 13, t.sub, 600);
        link.setTypeface(Theme.flex(this, 88, 600));
        link.setSingleLine();
        link.setEllipsize(android.text.TextUtils.TruncateAt.END);
        link.setPadding(t.dp(12), t.dp(8), t.dp(12), t.dp(8));
        link.setBackground(t.round(t.sheet, Theme.R_S));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.topMargin = t.dp(14);
        body.addView(link, ll);
        detail = t.text(this, "", 14, t.sub, 400);
        detail.setLineSpacing(t.dp(6), 1f);
        detail.setPadding(0, t.dp(12), 0, 0);
        body.addView(detail);
        retry = t.pill(this, "重试", true);
        retry.setOnClickListener(v -> submit());
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(56));
        rl.topMargin = t.dp(28);
        body.addView(retry, rl);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(t.dp(14), bars.top + t.dp(10), t.dp(22), bars.bottom + t.dp(24));
                return WindowInsets.CONSUMED;
            });
        }
        return root;
    }

    private void submit() {
        Matcher m = URL.matcher(text == null ? "" : text);
        if (!m.find()) {
            fail("没有找到链接", "分享的内容里没有网址。可以在原 App 里复制链接，再从 Catchup 首页菜单“添加链接”。", false);
            return;
        }
        String host = Uri.parse(m.group()).getHost();
        title.setText("正在读这篇…");
        link.setText(m.group().replaceFirst("^https?://(www\\.)?", ""));
        link.setVisibility(View.VISIBLE);
        detail.setText("读完会整理成简报。");
        retry.setVisibility(View.GONE);
        loaderBox.setVisibility(View.VISIBLE);
        loader.start();
        Trace.log(this, "share.start", host == null ? "" : host);
        new Thread(() -> {
            long eventId = -1;
            String err = null;
            try {
                JSONObject r = Api.post(this, "/v1/shares",
                        new JSONObject().put("text", text).putOpt("subject", subject));
                eventId = r.optLong("event_id", -1);
            } catch (Exception e) {
                err = e.getMessage();
                Trace.log(this, "share.fail", e.getClass().getSimpleName() + ": " + err);
            }
            long id = eventId;
            String error = err;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                loader.stop();
                if (id < 0) {
                    fail("没读成", error == null ? "服务器没有返回结果，稍后再试一次。" : error, true);
                    return;
                }
                startActivity(new Intent(this, EventActivity.class).putExtra(EventActivity.EXTRA_ID, id));
                overridePendingTransition(R.anim.enter_up, R.anim.hold);
                finish();
            });
        }, "share").start();
    }

    private void fail(String head, String why, boolean canRetry) {
        loaderBox.setVisibility(View.GONE);
        link.setVisibility(View.GONE);
        title.setText(head);
        detail.setText(why);
        retry.setVisibility(canRetry ? View.VISIBLE : View.GONE);
    }
}
