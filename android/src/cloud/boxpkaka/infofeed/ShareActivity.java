package cloud.boxpkaka.infofeed;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
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
    private TextView title, detail, retry;
    private ObjectAnimator pulse;
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

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(t.dp(8), t.dp(40), 0, 0);
        body.addView(t.text(this, "分享到 Catchup", 13, t.sub, 700));
        title = t.text(this, "", 28, t.ink, 900);
        title.setLetterSpacing(-0.02f);
        title.setPadding(0, t.dp(12), 0, 0);
        body.addView(title);
        detail = t.text(this, "", 15, t.ink, 400);
        detail.setAlpha(0.78f);
        detail.setLineSpacing(t.dp(5), 1f);
        detail.setPadding(0, t.dp(14), 0, 0);
        body.addView(detail);
        retry = t.pill(this, "重试", true);
        retry.setOnClickListener(v -> submit());
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = t.dp(24);
        body.addView(retry, rl);
        root.addView(body);

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
        detail.setText((host == null ? "" : host + "\n") + "读完会整理成简报。");
        retry.setVisibility(View.GONE);
        startPulse();
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
                stopPulse();
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
        title.setText(head);
        detail.setText(why);
        retry.setVisibility(canRetry ? View.VISIBLE : View.GONE);
    }

    private void startPulse() {
        stopPulse();
        pulse = ObjectAnimator.ofFloat(title, View.ALPHA, 1f, 0.4f);
        pulse.setDuration(700);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.start();
    }

    private void stopPulse() {
        if (pulse != null) pulse.cancel();
        title.setAlpha(1f);
    }
}
