package cloud.boxpkaka.infofeed;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页的撤销条：右滑或在详情页点了“不需要”，这件事从列表里移走以后，撤销条从底下用弹簧升起，波浪线倒数，走完才把
 * “不需要”提交给服务器。倒数期间可以撤销，也可以选为什么不需要；选了原因就立即提交，服务器记下了记忆就说出记住了什么。
 * 列表里藏起、放回那一行由首页自己管（见 Host）。
 */
final class Undo {
    /** 撤销了：把 id 这一行放回列表（id 小于 0 时没有要放回的）。 */
    interface Host {
        void undone(long id);
    }

    private static final long UNDO_MS = 10_000;
    /**
     * “不需要”的固定原因：提交给服务器的值、按钮文字、选中后先显示的回应。误点直接撤销。
     * 上面一行“不想看这类”的几个候选从服务器取（/reasons），选中哪类就记哪类；“其他”先存档，攒几条再一起判断。
     */
    private static final String[][] REASONS = {
            {"source", "不想看这个来源", "正在记下…"},
            {"known", "已经知道了", "已记下"},
            {"other", "其他", "已存档，攒几条再一起判断"}};

    private final Activity a;
    private final Theme t;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout undoBar;
    private final TextView undoLabel, undoButton;
    private final Shapes.Wave undoCountdown;
    private final List<TextView> reasonChips = new ArrayList<>(); // 固定原因
    private final List<TextView> topicChips = new ArrayList<>(); // “不想看这类”的候选，每次移走时重新取
    private final HorizontalScrollView topicScroll;
    private final LinearLayout topicRow;
    private long pendingDismiss = -1;
    private long answeredFor = -1; // 撤销条正在等哪件事的“记住了”回应
    private final Runnable commitDismiss = () -> commitDismiss(null);
    private final Runnable hideUndoLater = this::hideUndo;

    Undo(Activity a, Theme t, Host host) {
        this.a = a;
        this.t = t;
        this.host = host;
        undoBar = new LinearLayout(a);
        undoBar.setOrientation(LinearLayout.VERTICAL);
        undoBar.setPadding(t.dp(20), t.dp(10), t.dp(10), t.dp(12));
        undoBar.setBackground(t.metal(t.inv, Theme.R_XL));
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        undoLabel = t.text(a, "", 14, t.onInv, 600);
        head.addView(undoLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        undoButton = t.text(a, "撤销", 14, t.invPri, 800);
        undoButton.setPadding(t.dp(12), t.dp(8), t.dp(12), t.dp(8));
        undoButton.setBackground(t.ripple(Color.TRANSPARENT, 99));
        undoButton.setOnClickListener(v -> undoDismiss());
        head.addView(undoButton);
        undoBar.addView(head);
        LinearLayout chips = new LinearLayout(a);
        for (String[] reason : REASONS) {
            TextView chip = t.text(a, reason[1], 13, t.onInv, 700);
            chip.setPadding(t.dp(12), t.dp(8), t.dp(12), t.dp(8));
            chip.setOnClickListener(v -> pickReason(chip, reason));
            chips.addView(chip, gapLp(3));
            reasonChips.add(chip);
        }
        topicRow = new LinearLayout(a);
        topicRow.setGravity(Gravity.CENTER_VERTICAL);
        topicScroll = new HorizontalScrollView(a);
        topicScroll.setHorizontalScrollBarEnabled(false);
        topicScroll.addView(topicRow);
        LinearLayout.LayoutParams tsl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tsl.topMargin = t.dp(6);
        undoBar.addView(topicScroll, tsl);
        HorizontalScrollView chipScroll = new HorizontalScrollView(a);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chips);
        LinearLayout.LayoutParams csl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        csl.topMargin = t.dp(6);
        undoBar.addView(chipScroll, csl);
        undoCountdown = new Shapes.Wave(a, t.invPri);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(10));
        wl.setMargins(0, t.dp(12), t.dp(10), 0);
        undoBar.addView(undoCountdown, wl);
        undoBar.setVisibility(View.GONE);
    }

    /** 撤销条本身，由首页摆在底部。 */
    View view() {
        return undoBar;
    }

    /** 还在撤销期内的那一件，没有时是 -1：首页刷新后接着藏着它。 */
    long pending() {
        return pendingDismiss;
    }

    /**
     * id 这件事已经从列表里移走：撤销条升起、开始倒数。source 是这件事的主要来源名，“不想看这个来源”按钮直接写出来，
     * 免得不知道屏蔽的是谁。上一条还在撤销期内的，调用前先 commit。
     */
    void show(long id, String source) {
        pendingDismiss = id;
        Trace.log(a, "ui.dismiss", "id=" + pendingDismiss);
        main.removeCallbacks(hideUndoLater);
        resetUndo(source);
        loadTopics(pendingDismiss);
        // 撤销条从底下用弹簧升起，波浪线开始倒数，走完就提交
        undoBar.animate().cancel();
        undoBar.setTranslationY(t.dp(160));
        undoBar.setAlpha(1f);
        undoBar.setVisibility(View.VISIBLE);
        undoBar.animate().translationY(0).setInterpolator(Spring.STD).setDuration(Spring.STD.duration).start();
        undoCountdown.countdown(UNDO_MS);
        main.removeCallbacks(commitDismiss);
        main.postDelayed(commitDismiss, UNDO_MS);
    }

    /** 撤销期内的那一件不等倒数走完、不带原因直接提交（离开首页、又移走了一件）。 */
    void commit() {
        commitDismiss(null);
    }

    private void undoDismiss() {
        main.removeCallbacks(commitDismiss);
        long id = pendingDismiss;
        pendingDismiss = -1;
        hideUndo();
        host.undone(id);
    }

    private void resetUndo(String source) {
        undoLabel.setText("已移走，为什么不需要？");
        undoButton.setVisibility(View.VISIBLE);
        undoCountdown.setVisibility(View.VISIBLE);
        answeredFor = -1;
        for (int i = 0; i < REASONS.length; i++) {
            reasonChips.get(i).setText("source".equals(REASONS[i][0]) && !source.isEmpty()
                    ? "不想看「" + source + "」" : REASONS[i][1]);
        }
        styleChips(reasonChips);
        topicChips.clear();
        topicRow.removeAllViews();
        topicRow.addView(t.text(a, "不想看这类  正在想…", 13, (t.onInv & 0x00FFFFFF) | 0x99000000, 600));
        topicScroll.setVisibility(View.VISIBLE);
    }

    /** 一行原因按钮排成分段胶囊：头尾大圆角，中间小圆角。 */
    private void styleChips(List<TextView> row) {
        for (int i = 0; i < row.size(); i++) {
            TextView chip = row.get(i);
            float l = i == 0 ? 99 : Theme.R_XS, r = i == row.size() - 1 ? 99 : Theme.R_XS;
            chip.setEnabled(true);
            chip.setAlpha(1f);
            chip.setTextColor(t.onInv);
            chip.setBackground(t.ripple((t.onInv & 0x00FFFFFF) | 0x1F000000, l, r, r, l));
        }
    }

    /** 取这件事可能不想看的几类内容，放在撤销条上面一行；取不到就只留固定原因。 */
    private void loadTopics(long id) {
        Context app = a.getApplicationContext();
        new Thread(() -> {
            JSONArray options = null;
            try {
                options = Api.get(app, "/v1/events/" + id + "/reasons").optJSONArray("options");
            } catch (Exception err) {
                Trace.log(app, "reasons.fail", "id=" + id + " " + err);
            }
            JSONArray got = options;
            a.runOnUiThread(() -> {
                if (a.isDestroyed() || pendingDismiss != id || answeredFor >= 0) return; // 已经选了或者换了一条
                topicRow.removeAllViews();
                topicChips.clear();
                if (got == null || got.length() == 0) {
                    topicScroll.setVisibility(View.GONE);
                    return;
                }
                TextView label = t.text(a, "不想看这类", 13, (t.onInv & 0x00FFFFFF) | 0x99000000, 600);
                label.setPadding(0, 0, t.dp(8), 0);
                topicRow.addView(label);
                for (int i = 0; i < got.length(); i++) {
                    JSONObject option = got.optJSONObject(i);
                    TextView chip = t.text(a, option.optString("text"), 13, t.onInv, 700);
                    chip.setPadding(t.dp(12), t.dp(8), t.dp(12), t.dp(8));
                    chip.setOnClickListener(v -> pickTopic(chip, option));
                    topicRow.addView(chip, gapLp(3));
                    topicChips.add(chip);
                }
                styleChips(topicChips);
            });
        }, "reasons").start();
    }

    /** 选了固定原因就立即提交，撤销条留一下显示回应再收起。 */
    private void pickReason(TextView picked, String[] reason) {
        if (pendingDismiss < 0) return;
        boolean remembers = "source".equals(reason[0]); // 已经知道了、其他：不当场记
        picked(picked, reason[2], reason[0], remembers);
        commitDismiss(reason[0], null);
    }

    /** 选了“不想看这类”的某一类：原样记下这一类。 */
    private void pickTopic(TextView picked, JSONObject option) {
        if (pendingDismiss < 0) return;
        picked(picked, "正在记下…", "topic " + option.optString("text"), true);
        commitDismiss("topic", option);
    }

    private void picked(TextView picked, String response, String trace, boolean remembers) {
        List<TextView> all = new ArrayList<>(reasonChips);
        all.addAll(topicChips);
        for (TextView chip : all) {
            chip.setEnabled(false);
            if (chip != picked) chip.setAlpha(0.35f);
        }
        picked.setTextColor(t.inv);
        picked.setBackground(t.round(t.onInv, 99));
        picked.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        undoLabel.setText(response);
        undoButton.setVisibility(View.INVISIBLE);
        undoCountdown.stop();
        undoCountdown.setVisibility(View.GONE);
        Trace.log(a, "ui.dismiss.reason", "id=" + pendingDismiss + " " + trace);
        answeredFor = remembers ? pendingDismiss : -1;
        main.postDelayed(hideUndoLater, remembers ? 6000 : 1800); // 等不到服务器回应也会收起
    }

    /** 服务器按原因记下了记忆：在撤销条上说出记住了什么，和对话里的“记住了”一致。 */
    private void showRemembered(long id, JSONObject response) {
        if (id != answeredFor || undoBar.getVisibility() != View.VISIBLE) return;
        String said = Feedback.remembered(response);
        undoLabel.setText(said != null ? said : "已记下");
        main.removeCallbacks(hideUndoLater);
        main.postDelayed(hideUndoLater, 2400);
    }

    private void commitDismiss(String reason) {
        commitDismiss(reason, null);
    }

    /**
     * 把撤销期内的“不需要”提交给服务器；reason 为空表示没选原因（超时或离开页面），此时直接收起撤销条。
     * topic 是选中的那一类 {text, keywords}，原样传回服务器记下。
     */
    private void commitDismiss(String reason, JSONObject topic) {
        main.removeCallbacks(commitDismiss);
        long id = pendingDismiss;
        if (id < 0) return;
        pendingDismiss = -1;
        if (reason == null) hideUndo();
        Feedback.notNeeded(a, id, reason, topic, reason == null ? null : r -> {
            if (r != null) showRemembered(id, r);
        });
    }

    private void hideUndo() {
        if (undoBar.getVisibility() != View.VISIBLE) return;
        undoCountdown.stop();
        undoBar.animate().translationY(t.dp(24)).alpha(0f).setInterpolator(Spring.FX).setDuration(Spring.FX.duration)
                .withEndAction(() -> undoBar.setVisibility(View.GONE)).start();
    }

    private LinearLayout.LayoutParams gapLp(int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = t.dp(rightDp);
        return lp;
    }
}
