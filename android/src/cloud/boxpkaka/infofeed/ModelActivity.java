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
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HF 热榜上的一个模型：头部是任务分类、名字、热度和打开 HF 页面，下面是中文简介、参数量等模型信息，
 * 有对应事件的列出那件事，最后是问一问。榜上的模型不管有没有事件都进这里，数据就是榜单条目本身（见 TopicActivity）。
 */
public class ModelActivity extends Activity {
    static final String EXTRA_JSON = "json";
    static final String HF = "https://huggingface.co/";

    private Theme t;
    private Nav nav;
    private JSONObject x;
    private LinearLayout page, head, body;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        t = new Theme(this);
        t.applyWindow(this);
        try {
            x = new JSONObject(getIntent().getStringExtra(EXTRA_JSON));
        } catch (Exception e) {
            x = new JSONObject();
        }
        nav = Nav.of(this, build(), t.bg);
        setContentView(nav.wrap(state == null)); // 从点开的那一行展开，右滑返回
        Trace.log(this, "ui.model", x.optString("name"));
    }

    @Override
    public void finish() {
        nav.finish(super::finish);
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(t.bg);
        Flow mist = new Flow(this); // 整页的底：珍珠银的雾
        mist.mist(t, Theme.PEARL, 7);
        root.addView(mist, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        ScrollView scroll = new Elastic(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, 0, 0, t.dp(40));
        head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        page.addView(head);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(t.dp(22), t.dp(4), t.dp(22), 0);
        page.addView(body);
        scroll.addView(page);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        TextView back = t.backButton(this);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(t.dp(48), t.dp(48));
        bl.setMargins(t.dp(12), t.dp(10), 0, 0);
        root.addView(back, bl);
        buildHead();
        buildBody();
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                bl.topMargin = bars.top + t.dp(10);
                back.setLayoutParams(bl);
                head.setPadding(t.dp(22), bars.top + t.dp(68), t.dp(22), t.dp(26));
                page.setPadding(0, 0, 0, bars.bottom + t.dp(40));
                return WindowInsets.CONSUMED;
            });
        }
        return root;
    }

    /** 和事件详情一样的头部：分类标签和名次，“作者 /”小字在上、名字大字在下，热度和打开 HF 页面。 */
    private void buildHead() {
        head.setBackground(t.round(t.sheet, 0, 0, 44, 44));
        head.setPadding(t.dp(22), t.dp(68), t.dp(22), t.dp(26));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        String task = x.optString("task");
        if (!task.isEmpty()) {
            TextView chip = t.text(this, task, 12, t.ink, 700);
            chip.setPadding(t.dp(11), t.dp(4), t.dp(11), t.dp(4));
            chip.setBackground(t.round(t.chip, 99));
            top.addView(chip);
        }
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        if (!x.isNull("rank")) top.addView(t.text(this, "热榜第 " + x.optInt("rank") + " 名", 13, t.sub, 700));
        head.addView(top);

        String name = x.optString("name");
        int slash = name.indexOf('/');
        if (slash > 0) {
            TextView org = t.text(this, name.substring(0, slash) + " /", 15, t.sub, 700);
            org.setPadding(0, t.dp(14), 0, 0);
            head.addView(org);
        }
        TextView nm = t.text(this, name.substring(slash + 1), 27, t.ink, 900);
        nm.setLineSpacing(t.dp(7), 1f);
        nm.setLetterSpacing(-0.01f);
        nm.setPadding(0, t.dp(slash > 0 ? 2 : 14), 0, 0);
        head.addView(nm);

        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        metaRow.setPadding(0, t.dp(16), 0, 0);
        JSONObject extra = extra();
        List<String> parts = new ArrayList<>();
        if (extra.has("likes")) parts.add("♥ " + extra.optLong("likes"));
        if (extra.has("downloads")) parts.add("下载 " + String.format(Locale.US, "%,d", extra.optLong("downloads")));
        if (x.optInt("days") >= 2) parts.add("上榜第 " + x.optInt("days") + " 天");
        TextView meta = t.text(this, String.join("   ", parts), 13, t.sub, 700);
        meta.setTypeface(Theme.flex(this, 92, 700));
        metaRow.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView open = t.text(this, "HF 页面 ↗", 13, t.onInv, 700);
        open.setPadding(t.dp(14), t.dp(8), t.dp(14), t.dp(8));
        open.setBackground(t.ripple(t.inv, 99));
        open.setOnClickListener(v -> openUrl(x.optString("url")));
        metaRow.addView(open);
        head.addView(metaRow);
    }

    private void buildBody() {
        String summary = x.optString("summary");
        if (!summary.isEmpty()) {
            TextView s = t.text(this, summary, 15.5f, t.ink, 400);
            s.setLineSpacing(t.dp(6), 1f);
            s.setPadding(0, t.dp(24), 0, 0);
            body.addView(s);
        }

        body.addView(sectionLabel("模型信息"));
        JSONObject extra = extra();
        LinearLayout facts = new LinearLayout(this);
        facts.setOrientation(LinearLayout.VERTICAL);
        String size = extra.has("params") ? params(extra.optLong("params"))
                : extra.has("params_name") ? extra.optString("params_name") + "（名字里写的）" : "HF 上没有标注";
        facts.addView(fact("参数量", size));
        if (!extra.optString("precision").isEmpty()) facts.addView(fact("精度", extra.optString("precision")));
        if (!extra.optString("license").isEmpty()) {
            String license = extra.optString("license");
            facts.addView(fact("许可证", "other".equals(license) ? "自定的许可（见 HF 页面）" : license));
        }
        if (!extra.optString("base_model").isEmpty()) {
            facts.addView(fact(relation(extra.optString("base_relation")), extra.optString("base_model")));
        }
        if (!x.optString("task").isEmpty()) facts.addView(fact("任务", x.optString("task")));
        body.addView(facts);
        t.segments(facts);

        if (!x.isNull("event_id")) {
            body.addView(sectionLabel("相关报道"));
            LinearLayout events = new LinearLayout(this);
            events.setOrientation(LinearLayout.VERTICAL);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(t.dp(16), t.dp(14), t.dp(16), t.dp(14));
            TextView title = t.text(this, x.isNull("headline") ? name() : x.optString("headline"), 15, t.ink, 700);
            title.setLineSpacing(t.dp(3), 1f);
            row.addView(title);
            TextView more = t.text(this, "看要点和全部来源 →", 12.5f, t.sub, 700);
            more.setPadding(0, t.dp(6), 0, 0);
            row.addView(more);
            row.setOnClickListener(v -> Card.open(this, v, new Intent(this, EventActivity.class)
                    .putExtra(EventActivity.EXTRA_ID, x.optLong("event_id")).putExtra(EventActivity.EXTRA_FROM, "model")));
            events.addView(row);
            body.addView(events);
            t.segments(events);
        }

        TextView ask = t.text(this, "问一问", 15, 0, 800);
        ask.setGravity(Gravity.CENTER);
        t.askButton(ask, t.ask(Theme.PEARL), 99);
        String shortName = TopicsView.shortName(name());
        ask.setOnClickListener(v -> Push.open(this, new Intent(this, ChatActivity.class).putExtra(
                ChatActivity.EXTRA_QUESTIONS,
                new String[]{shortName + " 是做什么的？有什么特别之处？", shortName + " 和同类相比怎么样？"})));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(52));
        al.topMargin = t.dp(28);
        body.addView(ask, al);
    }

    private String name() {
        return x.optString("name");
    }

    private JSONObject extra() {
        JSONObject e = x.optJSONObject("extra");
        return e == null ? new JSONObject() : e;
    }

    private TextView sectionLabel(String text) {
        TextView v = t.text(this, text, 18, t.ink, 800);
        v.setPadding(0, t.dp(30), 0, t.dp(12));
        return v;
    }

    /** 模型信息的一行：左边灰字标签，右边墨色的值，值太长时折行。 */
    private View fact(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(t.dp(16), t.dp(14), t.dp(16), t.dp(14));
        row.addView(t.text(this, label, 14, t.sub, 600), new LinearLayout.LayoutParams(t.dp(76),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = t.text(this, value, 14, t.ink, 700);
        v.setGravity(Gravity.END);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    /** HF 标签里基座模型和这个模型的关系。 */
    private static String relation(String r) {
        switch (r) {
            case "finetune":
                return "微调自";
            case "quantized":
                return "量化自";
            case "adapter":
                return "适配自";
            case "merge":
                return "合并自";
            default:
                return "基于";
        }
    }

    /** 8.2B / 33.1B / 755B / 351M：三位有效数字以内，去掉多余的 .0。 */
    static String params(long n) {
        double v;
        String unit;
        if (n >= 1_000_000_000_000L) {
            v = n / 1e12;
            unit = "T";
        } else if (n >= 1_000_000_000L) {
            v = n / 1e9;
            unit = "B";
        } else if (n >= 1_000_000L) {
            v = n / 1e6;
            unit = "M";
        } else {
            v = n / 1e3;
            unit = "K";
        }
        return String.format(Locale.US, v >= 100 ? "%.0f" : "%.1f", v).replace(".0", "") + unit;
    }

    private void openUrl(String url) {
        if (url.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Trace.log(this, "model.open.fail", e.toString());
        }
    }
}
