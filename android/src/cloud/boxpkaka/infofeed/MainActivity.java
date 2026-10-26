package cloud.boxpkaka.infofeed;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 首页：顶部一句“补课”告诉你离开期间有几件值得知道的事，下面是“值得知道”的事件卡和其余动态。
 * 下拉刷新，左滑标记不需要（可撤销），点开看详情。
 */
public class MainActivity extends Activity {
    private static final int MAX_HIGHLIGHTS = 8;
    private static final int MAX_OTHERS = 40;
    private static final long REFETCH_MS = 30_000;
    private static final long UNDO_MS = 6000;
    /** “不需要”的原因：提交给服务器的值、按钮文字、选中后先显示的回应。误点直接撤销。 */
    private static final String[][] REASONS = {
            {"topic", "不想看这类", "正在记下…"},
            {"source", "不想看这个来源", "正在记下…"},
            {"known", "已经知道了", "好，这不影响同类内容"}};
    private static final int REQ_EVENT = 2;
    private static final String ANSWER = "answer"; // 追踪中的行里，“你问过的问题有答案了”用这个 label 区分

    private Theme t;
    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private LinearLayout topBar;
    private View topDivider;
    private ListView list;
    private PullLayout pull;
    private LinearLayout header;
    private TextView catchLine, catchSub, statusChip, digestText, skippedLine;
    private long digestFor = -1; // 已为哪个“上次离开时间”取过补课简报
    static volatile boolean visible; // 首页在前台：补到的旧推送不再弹通知，见 FeedService.notifyIfRecent
    private LinearLayout updateNotice, batteryNotice, notifyNotice;
    private TextView updateText, updateAction;
    private View skeleton;
    private ValueAnimator skeletonPulse;
    private LinearLayout stateView;
    private LinearLayout undoBar;
    private TextView undoLabel, undoButton;
    private final List<TextView> reasonChips = new ArrayList<>();

    private final List<Row> rows = new ArrayList<>();
    private final FeedAdapter adapter = new FeedAdapter();
    private JSONObject feed;
    private long seenAtOpen, lastFetch;
    private boolean loading, receiving;
    private final Set<Long> hidden = new HashSet<>();
    private long pendingDismiss = -1;
    private long answeredFor = -1; // 撤销条正在等哪件事的“记住了”回应
    private final Runnable commitDismiss = () -> commitDismiss(null);
    private final Runnable hideUndoLater = this::hideUndo;
    private Updater.Release release;

    private static final class Row {
        final int type; // 0 分组标题，1 事件卡，2 紧凑行
        final String label;
        final JSONObject e;

        Row(int type, String label, JSONObject e) {
            this.type = type;
            this.label = label;
            this.e = e;
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (FeedService.ACTION_PUSHED.equals(i.getAction())) load();
            else updateStatusChip();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        t = new Theme(this);
        t.applyWindow(this);
        setContentView(build());
        feed = Store.loadFeed(this);
        seenAtOpen = Store.seenAt(this);
        render();
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
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        // 在详情页点了“不需要”：回到首页后和左滑一样移除并给出撤销
        if (request == REQ_EVENT && result == RESULT_OK && data != null) {
            dismiss(data.getLongExtra(EventActivity.EXTRA_DISMISSED, -1));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Trace.log(this, "ui.resume", "service=" + FeedService.status);
        visible = true;
        seenAtOpen = Store.seenAt(this);
        if (!receiving) {
            IntentFilter f = new IntentFilter(FeedService.ACTION_UPDATE);
            f.addAction(FeedService.ACTION_PUSHED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(receiver, f);
            receiving = true;
        }
        updateStatusChip();
        batteryNotice.setVisibility(getSystemService(PowerManager.class)
                .isIgnoringBatteryOptimizations(getPackageName()) ? View.GONE : View.VISIBLE);
        notifyNotice.setVisibility(notificationsBlocked() ? View.VISIBLE : View.GONE);
        render();
        if (System.currentTimeMillis() - lastFetch > REFETCH_MS) load();
        if (Updater.due(this)) checkUpdate(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Trace.log(this, "ui.pause", "");
        visible = false;
        if (receiving) {
            unregisterReceiver(receiver);
            receiving = false;
        }
        if (feed != null) Store.setSeenAt(this, System.currentTimeMillis()); // 看过的事件下次不再算“新的”
        commitDismiss(null);
    }

    // ---------- 布局 ----------

    private View build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(t.bg);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, match());

        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(t.dp(20), t.dp(12), t.dp(8), t.dp(8));
        topBar.setBackgroundColor(t.bg);
        topBar.addView(wordmark(24), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView ask = t.pill(this, "问一问", false);
        ask.setTextSize(13);
        ask.setPadding(t.dp(14), t.dp(8), t.dp(14), t.dp(8));
        ask.setOnClickListener(v -> {
            startActivity(new Intent(this, ChatActivity.class));
            overridePendingTransition(R.anim.enter_up, R.anim.hold);
        });
        topBar.addView(ask);
        TextView menu = t.text(this, "⋯", 22, t.ink, 700);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("菜单");
        menu.setBackground(t.ripple(Color.TRANSPARENT, 22));
        menu.setOnClickListener(v -> showMenu());
        topBar.addView(menu, new LinearLayout.LayoutParams(t.dp(44), t.dp(44)));
        column.addView(topBar);
        topDivider = new View(this);
        topDivider.setBackgroundColor(t.line);
        topDivider.setAlpha(0);
        column.addView(topDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setClipToPadding(false);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setPadding(0, 0, 0, t.dp(96));
        list.addHeaderView(buildHeader(), null, false);
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView v, int s) {
            }

            @Override
            public void onScroll(AbsListView v, int first, int visible, int total) {
                boolean scrolled = first > 0 || (v.getChildCount() > 0 && v.getChildAt(0).getTop() < 0);
                topDivider.setAlpha(scrolled ? 1f : 0f);
            }
        });
        pull = new PullLayout(this, t, list);
        pull.setOnRefresh(this::load);
        column.addView(pull, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        skeleton = buildSkeleton();
        root.addView(skeleton, match());
        stateView = new LinearLayout(this);
        stateView.setOrientation(LinearLayout.VERTICAL);
        stateView.setGravity(Gravity.CENTER_VERTICAL);
        stateView.setPadding(t.dp(28), 0, t.dp(28), 0);
        stateView.setBackgroundColor(t.bg);
        stateView.setVisibility(View.GONE);
        root.addView(stateView, match());

        undoBar = new LinearLayout(this);
        undoBar.setOrientation(LinearLayout.VERTICAL);
        undoBar.setPadding(t.dp(18), t.dp(8), t.dp(10), t.dp(14));
        undoBar.setBackground(t.round(t.ink, 18));
        undoBar.setElevation(t.dp(6));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        undoLabel = t.text(this, "", 14, t.bg, 500);
        head.addView(undoLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        undoButton = t.text(this, "撤销", 14, t.bg, 800);
        undoButton.setPadding(t.dp(14), t.dp(8), t.dp(14), t.dp(8));
        undoButton.setBackground(t.ripple(Color.TRANSPARENT, 12));
        undoButton.setOnClickListener(v -> undoDismiss());
        head.addView(undoButton);
        undoBar.addView(head);
        LinearLayout chips = new LinearLayout(this);
        for (String[] reason : REASONS) {
            TextView chip = t.text(this, reason[1], 13, t.bg, 600);
            chip.setPadding(t.dp(12), t.dp(8), t.dp(12), t.dp(8));
            chip.setOnClickListener(v -> pickReason(chip, reason));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.rightMargin = t.dp(8);
            chips.addView(chip, cl);
            reasonChips.add(chip);
        }
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chips);
        LinearLayout.LayoutParams csl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        csl.topMargin = t.dp(2);
        undoBar.addView(chipScroll, csl);
        undoBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams ub = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        ub.setMargins(t.dp(16), 0, t.dp(16), t.dp(16));
        root.addView(undoBar, ub);

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                topBar.setPadding(t.dp(20), bars.top + t.dp(12), t.dp(8), t.dp(8));
                skeleton.setPadding(0, bars.top + t.dp(88), 0, 0);
                list.setPadding(0, 0, 0, bars.bottom + t.dp(96));
                ub.bottomMargin = bars.bottom + t.dp(16);
                undoBar.setLayoutParams(ub);
                return WindowInsets.CONSUMED;
            });
        }
        return root;
    }

    private View buildHeader() {
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(t.dp(20), t.dp(8), t.dp(20), t.dp(4));

        statusChip = t.text(this, "", 12, Theme.CINNABAR, 500);
        statusChip.setPadding(0, 0, 0, t.dp(12));
        statusChip.setVisibility(View.GONE);
        header.addView(statusChip);

        updateNotice = notice(t.ink);
        updateText = t.text(this, "", 14, t.bg, 600);
        updateNotice.addView(updateText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        updateAction = t.text(this, "更新", 13, t.ink, 800);
        updateAction.setPadding(t.dp(14), t.dp(7), t.dp(14), t.dp(7));
        updateAction.setBackground(t.round(t.bg, 99));
        updateNotice.addView(updateAction);
        updateNotice.setOnClickListener(v -> installUpdate());
        updateNotice.setVisibility(View.GONE);
        header.addView(updateNotice, noticeLp());

        batteryNotice = notice(t.sheet);
        LinearLayout bt = new LinearLayout(this);
        bt.setOrientation(LinearLayout.VERTICAL);
        bt.addView(t.text(this, "允许后台运行", 14, t.ink, 600));
        TextView bs = t.text(this, "关闭电池优化后，推送才不会延迟", 12, t.sub, 400);
        bs.setPadding(0, t.dp(4), 0, 0);
        bt.addView(bs);
        batteryNotice.addView(bt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        batteryNotice.addView(t.text(this, "去设置", 13, t.ink, 700));
        batteryNotice.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        header.addView(batteryNotice, noticeLp());

        notifyNotice = notice(t.sheet);
        LinearLayout nt = new LinearLayout(this);
        nt.setOrientation(LinearLayout.VERTICAL);
        nt.addView(t.text(this, "推送通知被关闭了", 14, t.ink, 600));
        TextView ns = t.text(this, "打开“新消息”通知，值得知道的事才会弹出来", 12, t.sub, 400);
        ns.setPadding(0, t.dp(4), 0, 0);
        nt.addView(ns);
        notifyNotice.addView(nt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        notifyNotice.addView(t.text(this, "去打开", 13, t.ink, 700));
        notifyNotice.setOnClickListener(v -> startActivity(
                new Intent(getSystemService(NotificationManager.class).areNotificationsEnabled()
                        ? Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS : Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                        .putExtra(Settings.EXTRA_CHANNEL_ID, FeedService.CHANNEL_MESSAGES)));
        header.addView(notifyNotice, noticeLp());

        catchLine = t.text(this, "", 30, t.ink, 900);
        catchLine.setLineSpacing(t.dp(4), 1f);
        catchLine.setLetterSpacing(-0.02f);
        catchLine.setPadding(0, t.dp(14), 0, 0);
        header.addView(catchLine);
        digestText = t.text(this, "", 15, t.ink, 400);
        digestText.setAlpha(0.86f);
        digestText.setLineSpacing(t.dp(6), 1f);
        digestText.setPadding(0, t.dp(14), 0, 0);
        digestText.setVisibility(View.GONE);
        header.addView(digestText);
        catchSub = t.text(this, "", 13, t.sub, 400);
        catchSub.setPadding(0, t.dp(12), 0, t.dp(10));
        header.addView(catchSub);
        skippedLine = t.text(this, "", 13, t.ink, 600);
        skippedLine.setPadding(t.dp(12), t.dp(8), t.dp(12), t.dp(8));
        skippedLine.setBackground(t.ripple(t.chip, 99));
        skippedLine.setOnClickListener(v -> showSkipped());
        skippedLine.setVisibility(View.GONE);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.bottomMargin = t.dp(10);
        header.addView(skippedLine, sl);
        return header;
    }

    private View buildSkeleton() {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setBackgroundColor(t.bg);
        s.setPadding(0, t.dp(88), 0, 0);
        s.addView(bar(220, 26, 20, 10));
        s.addView(bar(160, 26, 20, 16));
        s.addView(bar(260, 12, 20, 28));
        for (int i = 0; i < 3; i++) {
            View card = new View(this);
            card.setBackground(t.round(t.sheet, 22));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(150));
            lp.setMargins(t.dp(16), t.dp(6), t.dp(16), t.dp(6));
            s.addView(card, lp);
        }
        skeletonPulse = ObjectAnimator.ofFloat(s, View.ALPHA, 1f, 0.55f);
        skeletonPulse.setDuration(800);
        skeletonPulse.setRepeatMode(ValueAnimator.REVERSE);
        skeletonPulse.setRepeatCount(ValueAnimator.INFINITE);
        skeletonPulse.start();
        return s;
    }

    private View bar(int widthDp, int heightDp, int leftDp, int bottomDp) {
        View v = new View(this);
        v.setBackground(t.round(t.line, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(t.dp(widthDp), t.dp(heightDp));
        lp.setMargins(t.dp(leftDp), 0, 0, t.dp(bottomDp));
        v.setLayoutParams(lp);
        return v;
    }

    // ---------- 数据 ----------

    private void load() {
        if (loading) return;
        loading = true;
        new Thread(() -> {
            JSONObject f = null;
            Exception err = null;
            int pendingDevice = -1;
            try {
                f = Api.get(this, "/v1/feed");
                Store.saveFeed(this, f);
            } catch (Exception e) {
                err = e;
                if (e instanceof Api.Failure && ((Api.Failure) e).code == 403) {
                    try {
                        pendingDevice = Api.get(this, "/v1/me").optInt("id", -1);
                    } catch (Exception ignored) {
                        // 拿不到 id 也照样显示待确认页
                    }
                }
                Trace.log(this, "feed.fail", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            JSONObject got = f;
            Exception error = err;
            int device = pendingDevice;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                loading = false;
                pull.done();
                if (got != null) {
                    lastFetch = System.currentTimeMillis();
                    feed = got;
                    hidden.clear();
                    if (pendingDismiss >= 0) hidden.add(pendingDismiss);
                    updateStatusChip(); // 清掉“刷新失败”，只保留推送连接的状态
                    render();
                } else {
                    onLoadFailed(error, device);
                }
            });
        }, "feed-load").start();
    }

    private void onLoadFailed(Exception e, int pendingDevice) {
        if (e instanceof Api.Failure && ((Api.Failure) e).code == 403) {
            showState("这台手机还没被确认",
                    "在服务器上运行下面的命令，再回来点“重新检查”：\n\npython3 -m catchup devices approve "
                            + (pendingDevice >= 0 ? pendingDevice : "<设备编号>"));
            return;
        }
        if (feed == null) {
            showState("连不上服务器", "检查网络后重试。\n" + reason(e));
        } else {
            statusChip.setText("刷新失败：" + reason(e));
            statusChip.setVisibility(View.VISIBLE);
        }
    }

    private void showState(String title, String body) {
        stopSkeleton();
        stateView.removeAllViews();
        stateView.addView(t.text(this, title, 26, t.ink, 900));
        TextView b = t.text(this, body, 14, t.sub, 400);
        b.setLineSpacing(t.dp(3), 1f);
        b.setTextIsSelectable(true);
        b.setPadding(0, t.dp(14), 0, t.dp(28));
        stateView.addView(b);
        TextView retry = t.text(this, "重新检查", 15, t.bg, 800);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(0, t.dp(15), 0, t.dp(15));
        retry.setBackground(t.ripple(t.ink, 99));
        retry.setOnClickListener(v -> {
            stateView.setVisibility(View.GONE);
            load();
        });
        stateView.addView(retry);
        stateView.setVisibility(View.VISIBLE);
    }

    private void render() {
        if (feed == null) return;
        stopSkeleton();
        stateView.setVisibility(View.GONE);
        JSONArray all = feed.optJSONArray("events");
        List<JSONObject> highlights = new ArrayList<>(), others = new ArrayList<>();
        Set<String> sources = new LinkedHashSet<>();
        int newHighlights = 0;
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject e = all.optJSONObject(i);
            if (e == null || hidden.contains(e.optLong("id"))) continue;
            JSONArray src = e.optJSONArray("sources");
            for (int j = 0; src != null && j < src.length(); j++) sources.add(src.optString(j));
            if (e.optBoolean("highlight") && highlights.size() < MAX_HIGHLIGHTS) {
                highlights.add(e);
                if (isNew(e)) newHighlights++;
            } else if (others.size() < MAX_OTHERS) {
                others.add(e);
            }
        }
        others.sort((a, b) -> Long.compare(Theme.millis(b.optString("time")), Theme.millis(a.optString("time"))));

        catchLine.setText(catchUpLine(highlights.size(), newHighlights));
        int total = all == null ? 0 : all.length();
        catchSub.setText("过去三天整理了 " + total + " 件事，来自 " + sources.size() + " 个来源");
        JSONArray skipped = feed.optJSONArray("skipped");
        int skippedCount = skipped == null ? 0 : skipped.length();
        skippedLine.setText("替你略过 " + skippedCount + " 件热门但和你无关的事 ›");
        skippedLine.setVisibility(skippedCount > 0 ? View.VISIBLE : View.GONE);

        rows.clear();
        JSONArray follows = feed.optJSONArray("following"), answers = feed.optJSONArray("answers");
        int followCount = follows == null ? 0 : follows.length(), answerCount = answers == null ? 0 : answers.length();
        if (followCount + answerCount > 0) rows.add(new Row(0, "追踪中", null));
        for (int i = 0; i < answerCount; i++) rows.add(new Row(3, ANSWER, answers.optJSONObject(i)));
        for (int i = 0; i < followCount; i++) rows.add(new Row(3, null, follows.optJSONObject(i)));
        if (!highlights.isEmpty()) rows.add(new Row(0, "值得知道", null));
        for (JSONObject e : highlights) rows.add(new Row(1, null, e));
        if (!others.isEmpty()) rows.add(new Row(0, "其他动态", null));
        for (JSONObject e : others) rows.add(new Row(2, null, e));
        adapter.notifyDataSetChanged();
        if (newHighlights > 0 && digestFor != seenAtOpen) loadDigest(seenAtOpen);
        else if (newHighlights == 0) digestText.setVisibility(View.GONE);
    }

    /** 离开期间的补课简报：AI 把这段时间的重要事件写成一段话，放在首页大字下面。 */
    private void loadDigest(long since) {
        digestFor = since;
        new Thread(() -> {
            String text = "";
            try {
                text = Api.get(this, "/v1/digest?since=" + since).optString("text");
            } catch (Exception e) {
                Trace.log(this, "digest.fail", e.toString());
            }
            String got = text;
            runOnUiThread(() -> {
                if (isDestroyed() || got.isEmpty()) return;
                digestText.setText(got);
                digestText.setAlpha(0f);
                digestText.setVisibility(View.VISIBLE);
                digestText.animate().alpha(0.86f).setDuration(240).start();
            });
        }, "digest").start();
    }

    /** 首页最大的那句话：产品名字的由来。 */
    private String catchUpLine(int highlights, int fresh) {
        if (seenAtOpen == 0) {
            return highlights > 0 ? "为你整理了 " + highlights + " 件\n值得知道的事。" : "正在替你\n留意 AI 世界。";
        }
        if (fresh > 0) return "离开" + away(System.currentTimeMillis() - seenAtOpen) + "，\n有 " + fresh + " 件值得知道。";
        if (highlights > 0) return "值得知道的 " + highlights + " 件，\n你都看过了。";
        return "暂时没有\n值得打扰你的事。";
    }

    private static String away(long ms) {
        long h = ms / 3600_000;
        if (h < 1) return "不到 1 小时";
        if (h < 24) return " " + h + " 小时";
        return " " + (h / 24) + " 天";
    }

    /** 离开期间出现的，或离开期间推送过的（推送时可能没弹出来，回到首页要能找到）。 */
    private boolean isNew(JSONObject e) {
        return seenAtOpen > 0 && Math.max(Theme.millis(e.optString("first_seen")),
                Theme.millis(e.optString("pushed_at"))) > seenAtOpen;
    }

    /** 整个 App 的通知或“新消息”分类被关掉了；分类还没建（服务没启动过）时不算。 */
    private boolean notificationsBlocked() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = nm.getNotificationChannel(FeedService.CHANNEL_MESSAGES);
        return !nm.areNotificationsEnabled() || ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE;
    }

    private void stopSkeleton() {
        if (skeleton.getVisibility() == View.GONE) return;
        skeletonPulse.cancel();
        skeleton.animate().alpha(0f).setDuration(200).withEndAction(() -> skeleton.setVisibility(View.GONE)).start();
    }

    private void updateStatusChip() {
        boolean bad = FeedService.status.startsWith("连接断开");
        statusChip.setText(bad ? "推送连接断开，正在重试" : "");
        statusChip.setVisibility(bad ? View.VISIBLE : View.GONE);
    }

    // ---------- 列表 ----------

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
            Row r = rows.get(i);
            if (r.e == null) return -i - 1;
            if (ANSWER.equals(r.label)) return -2_000_000L - r.e.optLong("id");
            return r.type == 3 ? -1_000_000L - r.e.optLong("id") : r.e.optLong("id"); // 追踪的事件也可能出现在下面
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public int getViewTypeCount() {
            return 4;
        }

        @Override
        public int getItemViewType(int i) {
            return rows.get(i).type;
        }

        @Override
        public boolean isEnabled(int i) {
            return false; // 点击由每一行自己处理，便于和左滑共存
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Row r = rows.get(i);
            if (r.type == 0) {
                TextView label = convert != null ? (TextView) convert : sectionLabel();
                label.setText(r.label);
                return label;
            }
            if (r.type == 3) {
                if (convert == null) convert = newFollowRow();
                if (ANSWER.equals(r.label)) bindAnswer((Holder) convert.getTag(), r.e);
                else bindFollow((Holder) convert.getTag(), r.e);
                return convert;
            }
            if (convert == null) convert = r.type == 1 ? newCard() : newRow();
            Holder h = (Holder) convert.getTag();
            SwipeDismiss.reset(h.front, h.behind);
            if (r.type == 1) bindCard(h, r.e);
            else bindRow(h, r.e);
            h.front.setOnClickListener(v -> open(r.e));
            h.front.setOnTouchListener(new SwipeDismiss(h.front, h.behind, v -> dismiss(r.e.optLong("id"))));
            return convert;
        }
    }

    private static final class Holder {
        View front, behind, dot, whyRow;
        TextView chipA, chipB, headline, summary, why, meta, time;
    }

    private TextView sectionLabel() {
        TextView v = t.text(this, "", 13, t.sub, 700);
        v.setPadding(t.dp(20), t.dp(18), t.dp(20), t.dp(10));
        return v;
    }

    private FrameLayout swipeFrame(Holder h, View front) {
        FrameLayout frame = new FrameLayout(this);
        TextView behind = t.text(this, "不需要", 14, Theme.CINNABAR, 800);
        behind.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        behind.setPadding(0, 0, t.dp(28), 0);
        behind.setVisibility(View.INVISIBLE);
        frame.addView(behind, match());
        frame.addView(front, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        h.front = front;
        h.behind = behind;
        frame.setTag(h);
        return frame;
    }

    private View newCard() {
        Holder h = new Holder();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(t.dp(18), t.dp(16), t.dp(18), t.dp(16));
        card.setBackground(t.ripple(t.sheet, 22));
        card.setClickable(true);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        h.chipA = chip();
        h.chipB = chip();
        top.addView(h.chipA);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = t.dp(6);
        top.addView(h.chipB, gap);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        h.dot = dot();
        top.addView(h.dot, new LinearLayout.LayoutParams(t.dp(8), t.dp(8)));
        card.addView(top);

        h.headline = t.text(this, "", 18, t.ink, 700);
        h.headline.setLineSpacing(t.dp(3), 1f);
        h.headline.setPadding(0, t.dp(12), 0, 0);
        card.addView(h.headline);
        h.summary = t.text(this, "", 14.5f, t.ink, 400);
        h.summary.setAlpha(0.78f);
        h.summary.setLineSpacing(t.dp(4), 1f);
        h.summary.setMaxLines(3);
        h.summary.setEllipsize(TextUtils.TruncateAt.END);
        h.summary.setPadding(0, t.dp(8), 0, 0);
        card.addView(h.summary);

        LinearLayout whyRow = new LinearLayout(this);
        whyRow.setPadding(0, t.dp(12), 0, 0);
        View accent = new View(this);
        accent.setBackground(t.round(Theme.CINNABAR, 2));
        whyRow.addView(accent, new LinearLayout.LayoutParams(t.dp(3), ViewGroup.LayoutParams.MATCH_PARENT));
        h.why = t.text(this, "", 13.5f, t.ink, 500);
        h.why.setLineSpacing(t.dp(3), 1f);
        h.why.setPadding(t.dp(10), 0, 0, 0);
        whyRow.addView(h.why);
        h.whyRow = whyRow;
        card.addView(whyRow);

        h.meta = t.text(this, "", 12, t.sub, 500);
        h.meta.setFontFeatureSettings("tnum");
        h.meta.setPadding(0, t.dp(14), 0, 0);
        card.addView(h.meta);

        FrameLayout frame = swipeFrame(h, card);
        frame.setPadding(t.dp(16), t.dp(6), t.dp(16), t.dp(6));
        return frame;
    }

    private View newRow() {
        Holder h = new Holder();
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, t.dp(12), t.dp(20), t.dp(12));
        row.setBackground(t.ripple(t.bg, 0));
        row.setClickable(true);

        FrameLayout gutter = new FrameLayout(this);
        h.time = t.text(this, "", 12, t.sub, 500);
        h.time.setFontFeatureSettings("tnum");
        h.time.setGravity(Gravity.END);
        h.time.setPadding(0, t.dp(2), t.dp(14), 0);
        gutter.addView(h.time, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        h.dot = dot();
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(t.dp(7), t.dp(7));
        dl.leftMargin = t.dp(12);
        dl.topMargin = t.dp(6);
        gutter.addView(h.dot, dl);
        row.addView(gutter, new LinearLayout.LayoutParams(t.dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        h.headline = t.text(this, "", 15, t.ink, 600);
        h.headline.setLineSpacing(t.dp(3), 1f);
        h.headline.setMaxLines(2);
        h.headline.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(h.headline);
        h.meta = t.text(this, "", 12, t.sub, 400);
        h.meta.setPadding(0, t.dp(6), 0, 0);
        h.meta.setSingleLine();
        h.meta.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(h.meta);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return swipeFrame(h, row);
    }

    /** 追踪中的事件：标题 + 最近一条进展；上次看过之后有新进展的标朱砂点。 */
    private View newFollowRow() {
        Holder h = new Holder();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(t.dp(16), t.dp(14), t.dp(16), t.dp(14));
        card.setBackground(t.ripple(t.sheet, 18));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        h.headline = t.text(this, "", 15, t.ink, 700);
        h.headline.setMaxLines(2);
        h.headline.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(h.headline, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        h.dot = dot();
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(t.dp(8), t.dp(8));
        dl.leftMargin = t.dp(10);
        top.addView(h.dot, dl);
        card.addView(top);
        h.summary = t.text(this, "", 13.5f, t.ink, 400);
        h.summary.setAlpha(0.78f);
        h.summary.setLineSpacing(t.dp(3), 1f);
        h.summary.setMaxLines(2);
        h.summary.setEllipsize(TextUtils.TruncateAt.END);
        h.summary.setPadding(0, t.dp(6), 0, 0);
        card.addView(h.summary);
        h.meta = t.text(this, "", 12, t.sub, 500);
        h.meta.setPadding(0, t.dp(8), 0, 0);
        card.addView(h.meta);
        h.front = card;
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(t.dp(16), t.dp(4), t.dp(16), t.dp(4));
        frame.addView(card);
        frame.setTag(h);
        return frame;
    }

    private void bindFollow(Holder h, JSONObject f) {
        h.headline.setText(f.optString("headline"));
        String update = f.optString("update");
        show(h.summary, update.isEmpty() ? "还没有新进展，有实质变化时会告诉你" : update);
        int n = f.optInt("update_count");
        h.meta.setText((n > 0 ? n + " 条进展，" : "") + "最近 " + Theme.ago(Theme.millis(f.optString("updated"))));
        h.dot.setVisibility(n > 0 && seenAtOpen > 0 && Theme.millis(f.optString("updated")) > seenAtOpen
                ? View.VISIBLE : View.INVISIBLE);
        long id = f.optLong("id");
        h.front.setOnClickListener(v -> {
            startActivityForResult(new Intent(this, EventActivity.class).putExtra(EventActivity.EXTRA_ID, id), REQ_EVENT);
            overridePendingTransition(R.anim.enter_up, R.anim.hold);
        });
    }

    /** 替你留意的问题有了答案：点开回到当时的对话，答案已经写在里面。 */
    private void bindAnswer(Holder h, JSONObject a) {
        h.headline.setText("你问过：" + a.optString("question"));
        show(h.summary, a.optString("answer"));
        long at = Theme.millis(a.optString("answered"));
        h.meta.setText("有了答案 · " + Theme.ago(at));
        h.dot.setVisibility(seenAtOpen > 0 && at > seenAtOpen ? View.VISIBLE : View.INVISIBLE);
        Intent chat = new Intent(this, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_THREAD, a.optLong("thread_id"))
                .putExtra(ChatActivity.EXTRA_EVENT, a.optLong("event_id", -1))
                .putExtra(ChatActivity.EXTRA_TITLE, a.optString("question"));
        h.front.setOnClickListener(v -> {
            startActivity(chat);
            overridePendingTransition(R.anim.enter_up, R.anim.hold);
        });
    }

    private void bindCard(Holder h, JSONObject e) {
        JSONArray topics = e.optJSONArray("topics");
        show(h.chipA, topics != null && topics.length() > 0 ? topics.optString(0) : "");
        show(h.chipB, topics != null && topics.length() > 1 ? topics.optString(1) : "");
        h.dot.setVisibility(isNew(e) ? View.VISIBLE : View.INVISIBLE);
        h.headline.setText(e.optString("headline"));
        show(h.summary, e.optString("summary"));
        h.why.setText(e.optString("why"));
        h.whyRow.setVisibility(e.optString("why").isEmpty() ? View.GONE : View.VISIBLE);
        h.meta.setText(e.optInt("source_count") + " 个来源，" + Theme.ago(Theme.millis(e.optString("time")))
                + (e.optBoolean("pushed") ? " · 已推送" : ""));
    }

    private void bindRow(Holder h, JSONObject e) {
        h.time.setText(shortTime(Theme.millis(e.optString("time"))));
        h.dot.setVisibility(isNew(e) ? View.VISIBLE : View.INVISIBLE);
        h.headline.setText(e.optString("headline"));
        JSONArray src = e.optJSONArray("sources");
        String first = src != null && src.length() > 0 ? src.optString(0) : "";
        int n = e.optInt("source_count");
        h.meta.setText((n > 1 ? first + " 等 " + n + " 个来源" : first) + (e.optBoolean("pushed") ? " · 已推送" : ""));
    }

    private static String shortTime(long ms) {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        long start = today.getTimeInMillis();
        Date d = new Date(ms);
        if (ms >= start) return new SimpleDateFormat("HH:mm", Locale.CHINA).format(d);
        if (ms >= start - 86400_000L) return "昨天";
        return new SimpleDateFormat("M/d", Locale.CHINA).format(d);
    }

    private TextView chip() {
        TextView c = t.text(this, "", 11.5f, t.ink, 600);
        c.setPadding(t.dp(9), t.dp(4), t.dp(9), t.dp(4));
        c.setBackground(t.round(t.chip, 99));
        return c;
    }

    private View dot() {
        View d = new View(this);
        d.setBackground(t.round(Theme.CINNABAR, 99));
        return d;
    }

    private static void show(TextView v, String s) {
        v.setText(s);
        v.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---------- 打开与移除 ----------

    private void open(JSONObject e) {
        Intent i = new Intent(this, EventActivity.class)
                .putExtra(EventActivity.EXTRA_ID, e.optLong("id"))
                .putExtra(EventActivity.EXTRA_JSON, e.toString());
        startActivityForResult(i, REQ_EVENT);
        overridePendingTransition(R.anim.enter_up, R.anim.hold);
    }

    private void dismiss(long id) {
        if (id < 0) return;
        JSONObject e = findEvent(id); // 先取，render() 之后列表里就没有它了
        commitDismiss(null); // 上一条还在撤销期内的，先提交
        pendingDismiss = id;
        hidden.add(pendingDismiss);
        render();
        Trace.log(this, "ui.dismiss", "id=" + pendingDismiss);
        main.removeCallbacks(hideUndoLater);
        resetUndo(e == null ? "" : e.optString("source"));
        undoBar.animate().cancel();
        undoBar.setTranslationY(t.dp(24));
        undoBar.setAlpha(0f);
        undoBar.setVisibility(View.VISIBLE);
        undoBar.animate().translationY(0).alpha(1f).setDuration(180).start();
        main.removeCallbacks(commitDismiss);
        main.postDelayed(commitDismiss, UNDO_MS);
    }

    private void undoDismiss() {
        main.removeCallbacks(commitDismiss);
        if (pendingDismiss >= 0) hidden.remove(pendingDismiss);
        pendingDismiss = -1;
        hideUndo();
        render();
    }

    private JSONObject findEvent(long id) {
        for (Row r : rows) {
            if (r.e != null && r.e.optLong("id") == id) return r.e;
        }
        return null;
    }

    /** source 是这件事的主要来源名，“不想看这个来源”按钮直接写出来，免得不知道屏蔽的是谁。 */
    private void resetUndo(String source) {
        undoLabel.setText("已移走，为什么不需要？");
        undoButton.setVisibility(View.VISIBLE);
        answeredFor = -1;
        for (int i = 0; i < REASONS.length; i++) {
            reasonChips.get(i).setText("source".equals(REASONS[i][0]) && !source.isEmpty()
                    ? "不想看「" + source + "」" : REASONS[i][1]);
        }
        for (TextView chip : reasonChips) {
            chip.setEnabled(true);
            chip.setAlpha(1f);
            chip.setTextColor(t.bg);
            chip.setBackground(t.ripple((t.bg & 0x00FFFFFF) | 0x26000000, 99));
        }
    }

    /** 选了原因就立即提交，撤销条留一下显示回应再收起。 */
    private void pickReason(TextView picked, String[] reason) {
        if (pendingDismiss < 0) return;
        for (TextView chip : reasonChips) {
            chip.setEnabled(false);
            if (chip != picked) chip.setAlpha(0.35f);
        }
        picked.setTextColor(t.ink);
        picked.setBackground(t.round(t.bg, 99));
        picked.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
        undoLabel.setText(reason[2]);
        undoButton.setVisibility(View.INVISIBLE);
        Trace.log(this, "ui.dismiss.reason", "id=" + pendingDismiss + " " + reason[0]);
        boolean remembers = !"known".equals(reason[0]);
        answeredFor = remembers ? pendingDismiss : -1;
        commitDismiss(reason[0]);
        main.postDelayed(hideUndoLater, remembers ? 6000 : 1400); // 等不到服务器回应也会收起
    }

    /** 服务器按原因记下了记忆：在撤销条上说出记住了什么，和对话里的“记住了”一致。 */
    private void showRemembered(long id, JSONObject response) {
        if (isDestroyed() || id != answeredFor || undoBar.getVisibility() != View.VISIBLE) return;
        JSONObject memory = response == null ? null : response.optJSONObject("memory");
        JSONArray added = memory == null ? null : memory.optJSONArray("added");
        undoLabel.setText(added != null && added.length() > 0
                ? "记住了：" + added.optJSONObject(0).optString("text") : "好，已记下");
        main.removeCallbacks(hideUndoLater);
        main.postDelayed(hideUndoLater, 2400);
    }

    /** 把撤销期内的“不需要”提交给服务器；reason 为空表示没选原因（超时或离开页面），此时直接收起撤销条。 */
    private void commitDismiss(String reason) {
        main.removeCallbacks(commitDismiss);
        long id = pendingDismiss;
        if (id < 0) return;
        pendingDismiss = -1;
        if (reason == null) hideUndo();
        Context app = getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject r = Api.post(app, "/v1/events/" + id + "/feedback",
                        new JSONObject().put("value", "not_needed").putOpt("reason", reason));
                if (reason != null) runOnUiThread(() -> showRemembered(id, r));
            } catch (Exception err) {
                Trace.log(app, "feedback.fail", "id=" + id + " " + err);
            }
        }, "feedback").start();
    }

    private void hideUndo() {
        if (undoBar.getVisibility() != View.VISIBLE) return;
        undoBar.animate().translationY(t.dp(24)).alpha(0f).setDuration(160)
                .withEndAction(() -> undoBar.setVisibility(View.GONE)).start();
    }

    // ---------- 菜单 ----------

    private void showMenu() {
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        Dialog d = t.sheet(this, sheet);
        sheet.addView(menuRow("记忆", "Catchup 记得的关于你的事", () -> {
            d.dismiss();
            startActivity(new Intent(this, MemoryActivity.class));
            overridePendingTransition(R.anim.enter_up, R.anim.hold);
        }));
        sheet.addView(menuRow("添加链接", "从剪贴板", () -> {
            d.dismiss();
            addLinkFromClipboard();
        }));
        sheet.addView(menuRow("不需要的内容", "可以恢复", () -> {
            d.dismiss();
            showDismissed();
        }));
        sheet.addView(menuRow("检查更新", "当前 " + Updater.currentVersion(this), () -> {
            d.dismiss();
            checkUpdate(true);
        }));
        sheet.addView(menuRow("诊断信息", "", () -> {
            d.dismiss();
            showDiagnostics();
        }));
        sheet.addView(menuRow("后台运行设置", "", () -> {
            d.dismiss();
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }));
        d.show();
    }

    /** 标记过不需要的事件，点“恢复”后重新出现在首页。 */
    private void showDismissed() {
        new Thread(() -> {
            JSONArray list = null;
            try {
                list = Api.get(this, "/v1/dismissed").optJSONArray("events");
            } catch (Exception e) {
                Trace.log(this, "dismissed.fail", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            JSONArray got = list;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (got == null) {
                    toast("没有连上服务器，稍后再试");
                    return;
                }
                LinearLayout items = new LinearLayout(this);
                items.setOrientation(LinearLayout.VERTICAL);
                for (int i = 0; i < got.length(); i++) items.addView(dismissedRow(got.optJSONObject(i)));
                listSheet("不需要的内容", got.length() == 0 ? "最近 30 天没有标记为不需要的内容"
                        : "恢复后会重新出现在首页", items).show();
            });
        }, "dismissed").start();
    }

    /** 底部面板：标题、说明和一个长列表，列表最高占屏幕 3/5，其余滚动。 */
    private Dialog listSheet(String heading, String note, View items) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView title = t.text(this, heading, 20, t.ink, 800);
        title.setPadding(t.dp(24), t.dp(8), t.dp(24), t.dp(4));
        content.addView(title);
        TextView sub = t.text(this, note, 13, t.sub, 400);
        sub.setLineSpacing(t.dp(3), 1f);
        sub.setPadding(t.dp(24), t.dp(4), t.dp(24), t.dp(12));
        content.addView(sub);
        int maxHeight = getResources().getDisplayMetrics().heightPixels * 3 / 5;
        ScrollView scroll = new ScrollView(this) {
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(items);
        content.addView(scroll);
        return t.sheet(this, content);
    }

    /** 替你略过的：热度高但和你无关的事。点开看详情；点“想看这类”当场记成一条长期关注，以后同类的会排到前面。 */
    private void showSkipped() {
        JSONArray list = feed == null ? null : feed.optJSONArray("skipped");
        if (list == null || list.length() == 0) return;
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < list.length(); i++) items.addView(skippedRow(list.optJSONObject(i)));
        listSheet("替你略过的", "这几件热度不低，但和你的关注无关，所以没放进“值得知道”，也不会推送。"
                + "觉得该看的，点“想看这类”。", items).show();
        Trace.log(this, "ui.skipped", "n=" + list.length());
    }

    private View skippedRow(JSONObject s) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(t.dp(24), t.dp(12), t.dp(16), t.dp(12));
        row.setBackground(t.ripple(Color.TRANSPARENT, 0));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView headline = t.text(this, s.optString("headline"), 15, t.ink, 500);
        headline.setLineSpacing(t.dp(3), 1f);
        headline.setMaxLines(2);
        headline.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(headline);
        TextView meta = t.text(this, s.optInt("outlets") + " 家媒体报道 · " + s.optString("why"), 12, t.sub, 400);
        meta.setPadding(0, t.dp(5), 0, 0);
        text.addView(meta);
        TextView memo = t.text(this, "", 12, t.ink, 600);
        memo.setPadding(0, t.dp(6), 0, 0);
        memo.setVisibility(View.GONE);
        text.addView(memo);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView want = t.pill(this, "想看这类", false);
        want.setTextSize(13);
        want.setPadding(t.dp(14), t.dp(8), t.dp(14), t.dp(8));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.leftMargin = t.dp(12);
        row.addView(want, wl);
        long id = s.optLong("id");
        row.setOnClickListener(v -> {
            startActivityForResult(new Intent(this, EventActivity.class).putExtra(EventActivity.EXTRA_ID, id), REQ_EVENT);
            overridePendingTransition(R.anim.enter_up, R.anim.hold);
        });
        want.setOnClickListener(v -> {
            want.setEnabled(false);
            want.setText("记下中…");
            Context app = getApplicationContext();
            new Thread(() -> {
                JSONObject r = null;
                try {
                    r = Api.post(app, "/v1/events/" + id + "/feedback",
                            new JSONObject().put("value", "useful").put("reason", "topic"));
                } catch (Exception err) {
                    Trace.log(app, "feedback.fail", "id=" + id + " want " + err);
                }
                JSONObject got = r;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (got == null) {
                        want.setEnabled(true);
                        want.setText("想看这类");
                        toast("没有记下，请重试");
                        return;
                    }
                    want.setText("已记下");
                    want.setTextColor(t.sub);
                    want.setBackground(null);
                    JSONObject memory = got.optJSONObject("memory");
                    JSONArray added = memory == null ? null : memory.optJSONArray("added");
                    if (added != null && added.length() > 0) {
                        memo.setText("记住了：" + added.optJSONObject(0).optString("text"));
                        memo.setVisibility(View.VISIBLE);
                    }
                    lastFetch = 0;
                    load(); // 记忆变了，首页按新记忆重新排
                });
            }, "want").start();
            Trace.log(this, "ui.want", "id=" + id);
        });
        return row;
    }

    /** 微信等不支持系统分享的 App：复制链接后从菜单添加。 */
    private void addLinkFromClipboard() {
        android.content.ClipData clip = getSystemService(android.content.ClipboardManager.class).getPrimaryClip();
        CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this) : null;
        if (text == null || !text.toString().contains("http")) {
            toast("先在别的 App 里复制一条链接");
            return;
        }
        startActivity(new Intent(this, ShareActivity.class).putExtra(Intent.EXTRA_TEXT, text.toString()));
        overridePendingTransition(R.anim.enter_up, R.anim.hold);
    }

    private View dismissedRow(JSONObject e) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(t.dp(24), t.dp(12), t.dp(16), t.dp(12));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView headline = t.text(this, e.optString("headline"), 15, t.ink, 500);
        headline.setLineSpacing(t.dp(3), 1f);
        headline.setMaxLines(2);
        headline.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(headline);
        String reason = e.optString("reason");
        TextView when = t.text(this, "标记于 " + Theme.ago(Theme.millis(e.optString("at")))
                + (reason.isEmpty() ? "" : " · " + reason), 12, t.sub, 400);
        when.setPadding(0, t.dp(5), 0, 0);
        text.addView(when);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView restore = t.pill(this, "恢复", false);
        restore.setTextSize(13);
        restore.setPadding(t.dp(14), t.dp(8), t.dp(14), t.dp(8));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.leftMargin = t.dp(12);
        row.addView(restore, rl);
        long id = e.optLong("id");
        restore.setOnClickListener(v -> {
            restore.setEnabled(false);
            restore.setText("恢复中");
            Context app = getApplicationContext();
            new Thread(() -> {
                boolean ok = true;
                try {
                    Api.post(app, "/v1/events/" + id + "/feedback", new JSONObject().put("value", JSONObject.NULL));
                } catch (Exception err) {
                    ok = false;
                    Trace.log(app, "feedback.fail", "id=" + id + " restore " + err);
                }
                boolean done = ok;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (!done) {
                        restore.setEnabled(true);
                        restore.setText("恢复");
                        toast("恢复失败，请重试");
                        return;
                    }
                    restore.setText("已恢复");
                    restore.setTextColor(t.sub);
                    restore.setBackground(null);
                    hidden.remove(id);
                    lastFetch = 0;
                    load();
                });
            }, "restore").start();
            Trace.log(this, "ui.restore", "id=" + id);
        });
        return row;
    }

    private View menuRow(String label, String detail, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(t.dp(24), t.dp(16), t.dp(24), t.dp(16));
        row.setBackground(t.ripple(Color.TRANSPARENT, 0));
        row.addView(t.text(this, label, 16, t.ink, 500), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!detail.isEmpty()) row.addView(t.text(this, detail, 13, t.sub, 400));
        row.setOnClickListener(v -> action.run());
        return row;
    }

    /** 显示本机最近的埋点日志，可复制；不依赖网络。 */
    private void showDiagnostics() {
        String text = "v" + Updater.currentVersion(this) + " Android " + Build.VERSION.RELEASE + " " + Build.MODEL
                + "\n设备 " + Trace.device(this) + "  网络 " + Trace.network(this)
                + "\n状态 " + FeedService.status + "\n\n" + Trace.tail(this, 6000);
        TextView body = t.text(this, text, 11, t.ink, 400);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(t.dp(20), t.dp(12), t.dp(20), t.dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this)
                .setTitle("诊断信息")
                .setView(scroll)
                .setPositiveButton("复制", (dlg, w) -> {
                    getSystemService(android.content.ClipboardManager.class)
                            .setPrimaryClip(android.content.ClipData.newPlainText("Catchup 诊断信息", text));
                    toast("已复制");
                })
                .setNegativeButton("关闭", null)
                .show();
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
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
                if (isDestroyed()) return;
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

    // ---------- 小工具 ----------

    static String reason(Exception e) {
        if (e instanceof java.net.UnknownHostException) return "无法解析服务器地址";
        if (e instanceof java.net.SocketTimeoutException) return "连接超时";
        if (e instanceof java.net.ConnectException) return "无法访问服务器";
        if (e instanceof javax.net.ssl.SSLException) return "安全连接失败";
        return String.valueOf(e == null ? "未知错误" : e.getMessage());
    }

    /** 字标：小写粗体 catchup，末尾一个落在基线上的朱砂圆点，与图标里被 C 接住的点呼应。 */
    private TextView wordmark(int sp) {
        TextView v = t.text(this, "", sp, t.ink, 900);
        v.setLetterSpacing(-0.035f);
        SpannableString s = new SpannableString("catchup.");
        s.setSpan(new DotSpan(), 7, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        v.setText(s);
        return v;
    }

    private static final class DotSpan extends ReplacementSpan {
        @Override
        public int getSize(Paint p, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return Math.round(p.getTextSize() * 0.26f);
        }

        @Override
        public void draw(Canvas c, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint p) {
            float r = p.getTextSize() * 0.095f;
            int old = p.getColor();
            p.setColor(Theme.CINNABAR);
            c.drawCircle(x + p.getTextSize() * 0.06f + r, y - r, r, p);
            p.setColor(old);
        }
    }

    private LinearLayout notice(int color) {
        LinearLayout n = new LinearLayout(this);
        n.setGravity(Gravity.CENTER_VERTICAL);
        n.setPadding(t.dp(16), t.dp(14), t.dp(14), t.dp(14));
        n.setBackground(t.ripple(color, 18));
        return n;
    }

    private LinearLayout.LayoutParams noticeLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = t.dp(10);
        return lp;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
