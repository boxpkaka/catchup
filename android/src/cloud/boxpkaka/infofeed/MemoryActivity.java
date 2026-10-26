package cloud.boxpkaka.infofeed;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆页：Catchup 记得的关于你的事，按类别列出，都能改、能删、能手动添加；
 * 顶部是这一周的回顾，以及“根据记忆个性化”的总开关（关掉后不使用任何个人信息排序）。
 */
public class MemoryActivity extends Activity {
    private Theme t;
    private LinearLayout body;
    private final Map<String, String> kinds = new LinkedHashMap<>();
    private boolean personalize = true;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        t = new Theme(this);
        t.applyWindow(this);
        kinds.put("task", "正在做");
        kinds.put("interest", "长期关注");
        kinds.put("avoid", "不想看");
        kinds.put("source", "偏好的来源");
        kinds.put("avoid_source", "不想看的来源");
        kinds.put("style", "希望怎么写");
        setContentView(build());
        load();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.hold, R.anim.exit_down);
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(t.bg);
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(t.dp(20), t.dp(72), t.dp(20), t.dp(120));
        scroll.addView(body);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView back = t.backButton(this);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(t.dp(44), t.dp(44));
        bl.setMargins(t.dp(14), t.dp(14), 0, 0);
        root.addView(back, bl);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(t.dp(6), t.dp(6), t.dp(6), t.dp(6));
        actions.setBackground(t.round(t.sheet, 99));
        actions.setElevation(t.dp(8));
        TextView add = t.pill(this, "添加一条", false);
        add.setBackground(t.ripple(Color.TRANSPARENT, 99));
        add.setOnClickListener(v -> editor(null));
        actions.addView(add, weight());
        TextView miss = t.pill(this, "报告漏报", true);
        miss.setOnClickListener(v -> reportMiss());
        actions.addView(miss, weight());
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        al.setMargins(t.dp(16), 0, t.dp(16), t.dp(16));
        root.addView(actions, al);

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                bl.topMargin = bars.top + t.dp(10);
                back.setLayoutParams(bl);
                body.setPadding(t.dp(20), bars.top + t.dp(72), t.dp(20), bars.bottom + t.dp(120));
                al.bottomMargin = bars.bottom + t.dp(16);
                actions.setLayoutParams(al);
                return WindowInsets.CONSUMED;
            });
        }
        return root;
    }

    private void load() {
        new Thread(() -> {
            JSONObject r = null;
            try {
                r = Api.get(this, "/v1/memories");
            } catch (Exception e) {
                Trace.log(this, "memories.fail", e.toString());
            }
            JSONObject got = r;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (got == null) {
                    toast("加载失败，稍后再试");
                    return;
                }
                render(got);
            });
        }, "memories").start();
    }

    private void render(JSONObject r) {
        personalize = r.optBoolean("personalize", true);
        body.removeAllViews();
        TextView h = t.text(this, "Catchup 记得的\n关于你的事", 28, t.ink, 900);
        h.setLineSpacing(t.dp(4), 1f);
        body.addView(h);
        TextView sub = t.text(this, "它们决定给你推什么、怎么写。在事件下面聊聊你在做什么，这里会自动更新；每一条都能改或删。",
                14, t.sub, 400);
        sub.setLineSpacing(t.dp(4), 1f);
        sub.setPadding(0, t.dp(12), 0, t.dp(20));
        body.addView(sub);

        body.addView(week(r.optJSONObject("week")));
        body.addView(toggleRow());

        Map<String, List<JSONObject>> groups = new LinkedHashMap<>();
        for (String k : kinds.keySet()) groups.put(k, new ArrayList<>());
        JSONArray ms = r.optJSONArray("memories");
        for (int i = 0; ms != null && i < ms.length(); i++) {
            JSONObject m = ms.optJSONObject(i);
            List<JSONObject> g = groups.get(m.optString("kind"));
            if (g != null) g.add(m);
        }
        boolean any = false;
        for (Map.Entry<String, List<JSONObject>> g : groups.entrySet()) {
            if (g.getValue().isEmpty()) continue;
            any = true;
            TextView label = t.text(this, kinds.get(g.getKey()), 13, t.sub, 700);
            label.setPadding(0, t.dp(28), 0, t.dp(8));
            body.addView(label);
            for (JSONObject m : g.getValue()) body.addView(item(m));
        }
        if (!any) {
            TextView empty = t.text(this, "还没有记忆。\n打开任意一件事点“问一问”，聊聊你在做什么，Catchup 会记住。",
                    14, t.sub, 400);
            empty.setLineSpacing(t.dp(4), 1f);
            empty.setPadding(0, t.dp(32), 0, 0);
            body.addView(empty);
        }
        JSONArray qs = r.optJSONArray("questions");
        if (qs != null && qs.length() > 0) {
            TextView label = t.text(this, "替你留意的问题", 13, t.sub, 700);
            label.setPadding(0, t.dp(28), 0, t.dp(8));
            body.addView(label);
            for (int i = 0; i < qs.length(); i++) body.addView(question(qs.optJSONObject(i)));
        }
    }

    /** 对话里暂时没有答案的问题：还在等的可以取消；有了答案的点开回到当时的对话。 */
    private View question(JSONObject q) {
        boolean answered = "answered".equals(q.optString("status"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(t.dp(16), t.dp(14), t.dp(16), t.dp(14));
        row.setBackground(t.ripple(t.sheet, 16));
        TextView text = t.text(this, q.optString("text"), 15, t.ink, 500);
        text.setLineSpacing(t.dp(3), 1f);
        row.addView(text);
        if (answered) {
            TextView answer = t.text(this, q.optString("answer"), 14, t.ink, 400);
            answer.setAlpha(0.82f);
            answer.setLineSpacing(t.dp(3), 1f);
            answer.setPadding(0, t.dp(6), 0, 0);
            row.addView(answer);
        }
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, t.dp(6), 0, 0);
        bottom.addView(t.text(this, answered ? "有了答案，点开回到对话"
                        : Theme.ago(Theme.millis(q.optString("created"))) + "问的，有答案时告诉你", 12, t.sub, 400),
                weight());
        long id = q.optLong("id");
        if (answered) {
            row.setOnClickListener(v -> startActivity(new Intent(this, ChatActivity.class)
                    .putExtra(ChatActivity.EXTRA_THREAD, q.optLong("thread_id"))
                    .putExtra(ChatActivity.EXTRA_TITLE, q.optString("text"))));
        } else {
            TextView drop = t.text(this, "不用了", 12, t.ink, 700);
            drop.setPadding(t.dp(10), t.dp(4), t.dp(4), t.dp(4));
            drop.setOnClickListener(v -> {
                drop.setEnabled(false);
                post("/v1/questions/" + id, "{\"drop\": true}", this::load);
            });
            bottom.addView(drop);
        }
        row.addView(bottom);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = t.dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    /** 这一周的回顾：推送、反馈、对话、“要不是 Catchup 会错过”的比例。 */
    private View week(JSONObject w) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(t.dp(18), t.dp(16), t.dp(18), t.dp(18));
        card.setBackground(t.round(t.sheet, 20));
        card.addView(t.text(this, "这一周", 13, t.sub, 700));
        LinearLayout nums = new LinearLayout(this);
        nums.setPadding(0, t.dp(12), 0, 0);
        String[][] cells = {{"pushed", "推送"}, {"useful", "有用"}, {"not_needed", "不需要"}, {"chats", "对话"}};
        for (String[] c : cells) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            TextView n = t.text(this, String.valueOf(w == null ? 0 : w.optInt(c[0])), 24, t.ink, 800);
            n.setFontFeatureSettings("tnum");
            cell.addView(n);
            TextView l = t.text(this, c[1], 12, t.sub, 500);
            l.setPadding(0, t.dp(4), 0, 0);
            cell.addView(l);
            nums.addView(cell, weight());
        }
        card.addView(nums);
        if (w != null && w.optInt("rated") > 0) {
            TextView miss = t.text(this, "你评价过的 " + w.optInt("rated") + " 件里，有 " + w.optInt("would_miss")
                    + " 件要不是 Catchup 就会错过", 13, t.ink, 500);
            miss.setPadding(0, t.dp(14), 0, 0);
            card.addView(miss);
        }
        return card;
    }

    private View toggleRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(t.dp(2), t.dp(20), t.dp(2), t.dp(4));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(t.text(this, "根据记忆个性化", 15, t.ink, 600));
        TextView s = t.text(this, personalize ? "排序和“与你有关”会参考这些记忆" : "已关闭：按通用规则排序，不使用任何个人信息",
                12, t.sub, 400);
        s.setPadding(0, t.dp(4), 0, 0);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        FrameLayout track = new FrameLayout(this);
        track.setBackground(t.round(personalize ? t.ink : t.line, 99));
        View knob = new View(this);
        knob.setBackground(t.round(personalize ? t.bg : t.sheet, 99));
        FrameLayout.LayoutParams kl = new FrameLayout.LayoutParams(t.dp(22), t.dp(22),
                Gravity.CENTER_VERTICAL | (personalize ? Gravity.END : Gravity.START));
        kl.setMargins(t.dp(3), 0, t.dp(3), 0);
        track.addView(knob, kl);
        row.addView(track, new LinearLayout.LayoutParams(t.dp(48), t.dp(28)));
        row.setOnClickListener(v -> {
            boolean next = !personalize;
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            post("/v1/settings", "{\"personalize\": " + next + "}", this::load);
        });
        return row;
    }

    private View item(JSONObject m) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(t.dp(16), t.dp(14), t.dp(16), t.dp(14));
        row.setBackground(t.ripple(t.sheet, 16));
        TextView text = t.text(this, m.optString("text"), 15, t.ink, 500);
        text.setLineSpacing(t.dp(3), 1f);
        if (m.optBoolean("expired")) text.setAlpha(0.45f);
        row.addView(text);
        StringBuilder meta = new StringBuilder();
        String origin = m.optString("origin");
        String event = m.isNull("event") ? "" : m.optString("event");
        if ("对话".equals(origin) && !event.isEmpty()) meta.append("来自关于「").append(event).append("」的对话");
        else if ("对话".equals(origin)) meta.append("来自你和 Catchup 的对话");
        else meta.append("来自").append(origin.isEmpty() ? "你" : origin);
        meta.append("，").append(Theme.ago(Theme.millis(m.optString("updated"))));
        if (m.optBoolean("expired")) meta.append("，已过期，点开可续期");
        else if (!m.isNull("days_left")) meta.append("，").append(m.optInt("days_left")).append(" 天后不再算“正在做”");
        TextView mv = t.text(this, meta.toString(), 12, t.sub, 400);
        mv.setLineSpacing(t.dp(2), 1f);
        mv.setPadding(0, t.dp(6), 0, 0);
        row.addView(mv);
        row.setOnClickListener(v -> editor(m));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = t.dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    /** 编辑或新增一条记忆：选择类别、改文字；已有的可以删除。 */
    private void editor(JSONObject m) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(t.dp(22), t.dp(4), t.dp(22), 0);
        box.addView(t.text(this, m == null ? "告诉 Catchup 一件关于你的事" : "编辑这条记忆", 18, t.ink, 800));
        String[] chosen = {m == null ? "task" : m.optString("kind")};
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, t.dp(14), 0, t.dp(12));
        List<TextView> views = new ArrayList<>();
        for (Iterator<String> it = kinds.keySet().iterator(); it.hasNext(); ) {
            String k = it.next();
            TextView c = t.text(this, kinds.get(k), 12.5f, t.ink, 600);
            c.setPadding(t.dp(10), t.dp(7), t.dp(10), t.dp(7));
            c.setTag(k);
            c.setOnClickListener(v -> {
                chosen[0] = k;
                for (TextView x : views) styleChip(x, x.getTag().equals(chosen[0]));
            });
            views.add(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = t.dp(6);
            chips.addView(c, lp);
        }
        for (TextView x : views) styleChip(x, x.getTag().equals(chosen[0]));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        box.addView(hs);
        EditText input = field(m == null ? "比如：我在评估用 FunASR 替换 Whisper" : "");
        if (m != null) input.setText(m.optString("text"));
        box.addView(input);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, t.dp(16), 0, 0);
        Dialog[] d = new Dialog[1];
        if (m != null) {
            TextView del = t.pill(this, "删除", false);
            del.setTextColor(Theme.CINNABAR);
            del.setOnClickListener(v -> {
                d[0].dismiss();
                post("/v1/memories/" + m.optLong("id"), "{\"delete\": true}", this::load);
            });
            buttons.addView(del, weight());
            buttons.addView(new View(this), new LinearLayout.LayoutParams(t.dp(10), 1));
        }
        TextView save = t.pill(this, "保存", true);
        save.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;
            d[0].dismiss();
            try {
                JSONObject b = new JSONObject().put("kind", chosen[0]).put("text", text);
                if (m != null && m.optBoolean("expired")) b.put("renew", true);
                post(m == null ? "/v1/memories" : "/v1/memories/" + m.optLong("id"), b.toString(), this::load);
            } catch (Exception ignored) {
                // put 不会失败
            }
        });
        buttons.addView(save, weight());
        box.addView(buttons);
        d[0] = t.sheet(this, box);
        d[0].show();
        input.requestFocus();
    }

    private void reportMiss() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(t.dp(22), t.dp(4), t.dp(22), 0);
        box.addView(t.text(this, "有什么在别处看到、\n但 Catchup 没告诉你？", 18, t.ink, 800));
        TextView hint = t.text(this, "写下标题或链接，Catchup 会记住这类内容，以后留意。", 13, t.sub, 400);
        hint.setPadding(0, t.dp(8), 0, t.dp(12));
        box.addView(hint);
        EditText input = field("比如：Qwen3-TTS 开源了，我是在群里看到的");
        box.addView(input);
        TextView send = t.pill(this, "提交", true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = t.dp(16);
        box.addView(send, lp);
        Dialog d = t.sheet(this, box);
        send.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;
            d.dismiss();
            new Thread(() -> {
                String msg;
                try {
                    JSONObject r = Api.post(this, "/v1/misses", new JSONObject().put("text", text));
                    JSONArray added = r.optJSONArray("added");
                    msg = added != null && added.length() > 0 ? "已记下：" + added.optJSONObject(0).optString("text")
                            : "已记下，谢谢";
                } catch (Exception e) {
                    msg = "提交失败：" + MainActivity.reason(e);
                }
                String toastText = msg;
                runOnUiThread(() -> {
                    toast(toastText);
                    load();
                });
            }, "miss").start();
        });
        d.show();
        input.requestFocus();
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(t.sub);
        e.setTextColor(t.ink);
        e.setTextSize(15);
        e.setMinLines(2);
        e.setGravity(Gravity.TOP);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setPadding(t.dp(16), t.dp(14), t.dp(16), t.dp(14));
        e.setBackground(t.round(t.sheet, 16));
        return e;
    }

    private void styleChip(TextView c, boolean on) {
        c.setTextColor(on ? t.bg : t.ink);
        c.setBackground(t.round(on ? t.ink : t.chip, 99));
    }

    private void post(String path, String json, Runnable then) {
        new Thread(() -> {
            try {
                Api.post(this, path, new JSONObject(json));
            } catch (Exception e) {
                Trace.log(this, "post.fail", path + " " + e);
                runOnUiThread(() -> toast("操作失败：" + MainActivity.reason(e)));
            }
            runOnUiThread(() -> {
                if (!isDestroyed()) then.run();
            });
        }, "post").start();
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
