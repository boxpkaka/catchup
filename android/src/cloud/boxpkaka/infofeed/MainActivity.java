package cloud.boxpkaka.infofeed;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
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
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 首页，分“信息流”和“报告”两页。信息流按见报时间从新到旧、按天分组，每天先是“最值得看”的几件（见 top），
 * 大字、带编号，其余按时间排成一条线：重要性 4 以上加粗、带一行概要，其他只有标题。不按话题上色，话题写在来源前面；
 * 颜色只表示状态：钴蓝是新的，墨色反白的“要闻”是最重要的。最上面是追踪中，“未追踪信息”入口在第一天的最后，
 * 滑到底继续加载更早的。下拉刷新，左滑标记不需要（可撤销），点开看详情。
 * 报告页见 ReportView；推送的周报月报点开直接进报告页（EXTRA_REPORT）。
 */
public class MainActivity extends Activity {
    static final String EXTRA_REPORT = "report"; // 如 weekly/2026-10-19，见 FeedService
    private static final long REFETCH_MS = 30_000;
    private static final long OLDER_RETRY_MS = 10_000;
    private static final long UNDO_MS = 10_000;
    private static final double TOP_RATIO = 0.7; // 最值得看：分数不低于当天第一名的七成
    private static final int TOP_MAX = 5;
    /**
     * “不需要”的固定原因：提交给服务器的值、按钮文字、选中后先显示的回应。误点直接撤销。
     * 上面一行“不想看这类”的几个候选从服务器取（/reasons），选中哪类就记哪类；“其他”先存档，攒几条再一起判断。
     */
    private static final String[][] REASONS = {
            {"source", "不想看这个来源", "正在记下…"},
            {"known", "已经知道了", "已记下"},
            {"other", "其他", "已存档，攒几条再一起判断"}};
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
    private TextView statusChip;
    static volatile boolean visible; // 首页在前台：补到的旧推送不再弹通知，见 FeedService.notifyIfRecent
    private LinearLayout updateNotice, batteryNotice, notifyNotice;
    private TextView updateText, updateAction;
    private View skeleton;
    private Shapes.Loader skeletonLoader;
    private LinearLayout stateView;
    private LinearLayout undoBar;
    private TextView undoLabel, undoButton;
    private Shapes.Wave undoCountdown;
    private final List<TextView> reasonChips = new ArrayList<>(); // 固定原因
    private final List<TextView> topicChips = new ArrayList<>(); // “不想看这类”的候选，每次移走时重新取
    private HorizontalScrollView topicScroll;
    private LinearLayout topicRow;

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
    private TextView tabFeed, tabReport;
    private ReportView reports;
    private boolean reportTab;
    private final List<JSONObject> olderEvents = new ArrayList<>(); // 往前翻页加载的，接在第一页后面
    private String olderCursor; // 下一页的 before，到开始采集为止
    private boolean loadingOlder;
    private long olderRetryAt;

    private static final class Row {
        // 0 日期，1 最值得看（label 是编号），2 按时间的行，3 追踪中（追踪的事件和有了答案的问题），4 小标题，
        // 5 “未追踪信息”入口
        final int type;
        final CharSequence label;
        final JSONObject e;

        Row(int type, CharSequence label, JSONObject e) {
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
        olderCursor = cursor(feed);
        seenAtOpen = Store.seenAt(this);
        render();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        FeedService.start(this);
        onInstallResult(getIntent());
        openReport(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        onInstallResult(intent);
        openReport(intent);
    }

    @Override
    public void onBackPressed() {
        if (reportTab) selectTab(false);
        else super.onBackPressed();
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
        topBar.setPadding(t.dp(20), t.dp(10), t.dp(10), t.dp(6));
        topBar.setBackgroundColor(t.bg);
        topBar.addView(wordmark(), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView ask = t.text(this, "问一问", 13.5f, t.onLime, 700);
        ask.setGravity(Gravity.CENTER);
        t.press(ask, t.lime, 99);
        ask.setPadding(t.dp(16), t.dp(9), t.dp(16), t.dp(9));
        ask.setOnClickListener(v -> {
            startActivity(new Intent(this, ChatActivity.class));
            overridePendingTransition(R.anim.enter_up, R.anim.hold);
        });
        topBar.addView(ask);
        TextView menu = t.text(this, "⋮", 20, t.ink, 700);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("菜单");
        menu.setBackground(t.ripple(Color.TRANSPARENT, 99));
        menu.setOnClickListener(v -> showMenu());
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(t.dp(44), t.dp(44));
        ml.leftMargin = t.dp(4);
        topBar.addView(menu, ml);
        column.addView(topBar);
        column.addView(buildTabs());
        topDivider = new View(this);
        topDivider.setBackgroundColor(t.hi);
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
                if (total > 0 && first + visible >= total - 4) loadOlder();
            }
        });
        pull = new PullLayout(this, t, list);
        pull.setOnRefresh(this::load);
        FrameLayout content = new FrameLayout(this);
        content.addView(pull, match());
        reports = new ReportView(this, t, this::openEvent);
        reports.setVisibility(View.GONE);
        content.addView(reports, match());
        column.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

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
        undoBar.setPadding(t.dp(20), t.dp(10), t.dp(10), t.dp(12));
        undoBar.setBackground(t.round(t.inv, Theme.R_XL));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        undoLabel = t.text(this, "", 14, t.onInv, 600);
        head.addView(undoLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        undoButton = t.text(this, "撤销", 14, t.invPri, 800);
        undoButton.setPadding(t.dp(12), t.dp(8), t.dp(12), t.dp(8));
        undoButton.setBackground(t.ripple(Color.TRANSPARENT, 99));
        undoButton.setOnClickListener(v -> undoDismiss());
        head.addView(undoButton);
        undoBar.addView(head);
        LinearLayout chips = new LinearLayout(this);
        for (String[] reason : REASONS) {
            TextView chip = t.text(this, reason[1], 13, t.onInv, 700);
            chip.setPadding(t.dp(12), t.dp(8), t.dp(12), t.dp(8));
            chip.setOnClickListener(v -> pickReason(chip, reason));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.rightMargin = t.dp(3);
            chips.addView(chip, cl);
            reasonChips.add(chip);
        }
        topicRow = new LinearLayout(this);
        topicRow.setGravity(Gravity.CENTER_VERTICAL);
        topicScroll = new HorizontalScrollView(this);
        topicScroll.setHorizontalScrollBarEnabled(false);
        topicScroll.addView(topicRow);
        LinearLayout.LayoutParams tsl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tsl.topMargin = t.dp(6);
        undoBar.addView(topicScroll, tsl);
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chips);
        LinearLayout.LayoutParams csl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        csl.topMargin = t.dp(6);
        undoBar.addView(chipScroll, csl);
        undoCountdown = new Shapes.Wave(this, t.invPri);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(10));
        wl.setMargins(0, t.dp(12), t.dp(10), 0);
        undoBar.addView(undoCountdown, wl);
        undoBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams ub = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        ub.setMargins(t.dp(16), 0, t.dp(16), t.dp(16));
        root.addView(undoBar, ub);

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                topBar.setPadding(t.dp(20), bars.top + t.dp(10), t.dp(10), t.dp(6));
                skeleton.setPadding(0, bars.top + t.dp(60), 0, 0);
                list.setPadding(0, 0, 0, bars.bottom + t.dp(96));
                reports.setBottomInset(bars.bottom + t.dp(96));
                ub.bottomMargin = bars.bottom + t.dp(16);
                undoBar.setLayoutParams(ub);
                return WindowInsets.CONSUMED;
            });
        }
        return root;
    }

    /** 信息流 / 报告：顶栏下面两个胶囊标签，选中的是墨色反白。 */
    private View buildTabs() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(t.dp(16), t.dp(2), t.dp(16), t.dp(8));
        tabs.setBackgroundColor(t.bg);
        tabFeed = tab("信息流", false);
        tabReport = tab("报告", true);
        tabs.addView(tabFeed, gapLp(6));
        tabs.addView(tabReport);
        styleTabs();
        return tabs;
    }

    private TextView tab(String label, boolean report) {
        TextView v = t.text(this, label, 15, t.ink, 800);
        v.setPadding(t.dp(18), t.dp(8), t.dp(18), t.dp(8));
        v.setOnClickListener(x -> selectTab(report));
        return v;
    }

    private void styleTabs() {
        for (TextView v : new TextView[]{tabFeed, tabReport}) {
            boolean on = (v == tabReport) == reportTab;
            v.setTextColor(on ? t.onInv : t.sub);
            v.setBackground(t.ripple(on ? t.inv : Color.TRANSPARENT, 99));
        }
    }

    private void selectTab(boolean report) {
        if (report != reportTab) Trace.log(this, "ui.tab", report ? "report" : "feed");
        reportTab = report;
        styleTabs();
        pull.setVisibility(report ? View.GONE : View.VISIBLE);
        reports.setVisibility(report ? View.VISIBLE : View.GONE);
        topDivider.setAlpha(0f);
        if (report) reports.showIfEmpty();
    }

    /** 推送的周报、月报点开：进报告页并显示那一份。 */
    private void openReport(Intent intent) {
        String r = intent == null ? null : intent.getStringExtra(EXTRA_REPORT);
        if (r == null) return;
        intent.removeExtra(EXTRA_REPORT); // 转屏重建时不再重复打开
        String[] parts = r.split("/", 2);
        selectTab(true);
        reports.show(parts[0], parts.length > 1 ? parts[1] : null);
    }

    private void openEvent(long id) {
        startActivityForResult(new Intent(this, EventActivity.class).putExtra(EventActivity.EXTRA_ID, id), REQ_EVENT);
        overridePendingTransition(R.anim.enter_up, R.anim.hold);
    }

    private View buildHeader() {
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(t.dp(12), t.dp(4), t.dp(12), 0);

        // 提醒条：更新用反色，最显眼；其余用卡片色，右边是主色容器的按钮
        updateNotice = notice(t.inv);
        updateText = t.text(this, "", 14.5f, t.onInv, 700);
        updateNotice.addView(updateText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        updateAction = noticeAction("更新", t.lime, t.onLime);
        updateNotice.addView(updateAction);
        updateNotice.setOnClickListener(v -> installUpdate());
        updateNotice.setVisibility(View.GONE);
        header.addView(updateNotice, noticeLp());

        batteryNotice = notice(t.sheet);
        batteryNotice.addView(noticeText("允许后台运行", "关闭电池优化后，推送才不会延迟"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        batteryNotice.addView(noticeAction("去设置", t.pc, t.onPc));
        batteryNotice.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        header.addView(batteryNotice, noticeLp());

        notifyNotice = notice(t.sheet);
        notifyNotice.addView(noticeText("推送通知被关闭了", "打开“新消息”通知，值得知道的事才会弹出来"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        notifyNotice.addView(noticeAction("去打开", t.pc, t.onPc));
        notifyNotice.setOnClickListener(v -> startActivity(
                new Intent(getSystemService(NotificationManager.class).areNotificationsEnabled()
                        ? Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS : Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                        .putExtra(Settings.EXTRA_CHANNEL_ID, FeedService.CHANNEL_MESSAGES)));
        header.addView(notifyNotice, noticeLp());

        statusChip = t.text(this, "", 12.5f, t.onErr, 600);
        statusChip.setPadding(t.dp(10), t.dp(6), t.dp(10), 0);
        statusChip.setVisibility(View.GONE);
        header.addView(statusChip);
        return header;
    }

    /** 首次加载的骨架屏：形状和真实布局一致（追踪中一组、日期、最值得看），中间是加载指示器。 */
    private View buildSkeleton() {
        FrameLayout s = new FrameLayout(this);
        s.setBackgroundColor(t.bg);
        s.setPadding(0, t.dp(60), 0, 0);
        LinearLayout blocks = new LinearLayout(this);
        blocks.setOrientation(LinearLayout.VERTICAL);
        blocks.addView(block(-1, 136, Theme.R_XL, 36));
        blocks.addView(block(96, 32, Theme.R_S, 30));
        blocks.addView(block(-1, 180, Theme.R_XL, 44));
        blocks.addView(block(-1, 180, Theme.R_XS, 3));
        s.addView(blocks, match());
        skeletonLoader = new Shapes.Loader(this, t.pri);
        skeletonLoader.setPadding(t.dp(11), t.dp(11), t.dp(11), t.dp(11));
        skeletonLoader.setBackground(t.round(t.pc, 99));
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(t.dp(56), t.dp(56), Gravity.CENTER);
        s.addView(skeletonLoader, ll);
        skeletonLoader.start();
        return s;
    }

    private View block(int widthDp, int heightDp, float radiusDp, int topDp) {
        View v = new View(this);
        v.setBackground(t.round(t.hi, radiusDp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                widthDp < 0 ? ViewGroup.LayoutParams.MATCH_PARENT : t.dp(widthDp), t.dp(heightDp));
        lp.setMargins(t.dp(12), t.dp(topDp), t.dp(12), 0);
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
                    olderEvents.clear(); // 刷新后从第一页重新开始
                    olderCursor = cursor(got);
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

    /** 滑到底时加载更早的一页，接在后面；失败的话隔一会儿再试，不跟着每次滚动重复请求。 */
    private void loadOlder() {
        if (loadingOlder || olderCursor == null || System.currentTimeMillis() < olderRetryAt) return;
        loadingOlder = true;
        String before = olderCursor;
        new Thread(() -> {
            JSONObject page = null;
            try {
                page = Api.get(this, "/v1/feed?before=" + Uri.encode(before));
            } catch (Exception e) {
                Trace.log(this, "feed.older.fail", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            JSONObject got = page;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                loadingOlder = false;
                if (got == null) {
                    olderRetryAt = System.currentTimeMillis() + OLDER_RETRY_MS;
                    return;
                }
                if (!before.equals(olderCursor)) return; // 期间刷新过，这一页作废
                JSONArray events = got.optJSONArray("events");
                for (int i = 0; events != null && i < events.length(); i++) olderEvents.add(events.optJSONObject(i));
                olderCursor = cursor(got);
                render();
            });
        }, "feed-older").start();
    }

    private static String cursor(JSONObject page) {
        return page == null || page.isNull("older") ? null : page.optString("older", null);
    }

    private void onLoadFailed(Exception e, int pendingDevice) {
        if (e instanceof Api.Failure && ((Api.Failure) e).code == 403) {
            showState("?", "这台手机\n还没被确认", "在服务器上运行下面的命令，再回来点“重新检查”。",
                    "python3 -m catchup devices approve " + (pendingDevice >= 0 ? pendingDevice : "<设备编号>"));
            return;
        }
        if (feed == null) {
            showState("!", "连不上服务器", "检查网络后重试。\n" + reason(e), null);
        } else {
            statusChip.setText("刷新失败：" + reason(e));
            statusChip.setVisibility(View.VISIBLE);
        }
    }

    private void showState(String mark, String title, String body, String code) {
        stopSkeleton();
        stateView.removeAllViews();
        TextView art = t.text(this, mark, 44, t.sub, 900);
        art.setTypeface(Theme.flex(this, 151, 900));
        art.setGravity(Gravity.CENTER);
        art.setBackground(new Shapes.Blob(Shapes.CLOVER, t.hi));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(t.dp(120), t.dp(120));
        al.bottomMargin = t.dp(26);
        stateView.addView(art, al);
        TextView h = t.text(this, title, 26, t.ink, 900);
        h.setLineSpacing(t.dp(6), 1f);
        stateView.addView(h);
        TextView b = t.text(this, body, 14, t.sub, 400);
        b.setLineSpacing(t.dp(6), 1f);
        b.setPadding(0, t.dp(12), 0, 0);
        stateView.addView(b);
        if (code != null) {
            TextView c = t.text(this, code, 12.5f, t.ink, 400);
            c.setTypeface(Typeface.MONOSPACE);
            c.setTextIsSelectable(true);
            c.setLineSpacing(t.dp(4), 1f);
            c.setPadding(t.dp(14), t.dp(12), t.dp(14), t.dp(12));
            c.setBackground(t.round(t.sheet, Theme.R_M));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.topMargin = t.dp(14);
            stateView.addView(c, cl);
        }
        TextView retry = t.text(this, "重新检查", 15.5f, t.onPri, 800);
        retry.setGravity(Gravity.CENTER);
        t.press(retry, t.pri, 99);
        retry.setOnClickListener(v -> {
            stateView.setVisibility(View.GONE);
            load();
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(56));
        rl.topMargin = t.dp(28);
        stateView.addView(retry, rl);
        stateView.setVisibility(View.VISIBLE);
    }

    private void render() {
        if (feed == null) return;
        stopSkeleton();
        stateView.setVisibility(View.GONE);
        List<JSONObject> events = new ArrayList<>();
        JSONArray first = feed.optJSONArray("events");
        for (int i = 0; first != null && i < first.length(); i++) events.add(first.optJSONObject(i));
        events.addAll(olderEvents);

        rows.clear();
        JSONArray follows = feed.optJSONArray("following"), answers = feed.optJSONArray("answers");
        int followCount = follows == null ? 0 : follows.length(), answerCount = answers == null ? 0 : answers.length();
        if (followCount + answerCount > 0) rows.add(new Row(4, "追踪中", null));
        for (int i = 0; i < answerCount; i++) rows.add(new Row(3, ANSWER, answers.optJSONObject(i)));
        for (int i = 0; i < followCount; i++) rows.add(new Row(3, null, follows.optJSONObject(i)));
        String day = null;
        List<JSONObject> list = new ArrayList<>();
        int skipAt = -1; // “未追踪信息”入口放在第一天的最后
        for (JSONObject e : events) { // 服务器已按见报时间从新到旧排好
            if (e == null || hidden.contains(e.optLong("id"))) continue;
            String d = dayLabel(Theme.millis(e.optString("time")));
            if (!d.equals(day) && day != null) {
                addDay(list);
                if (skipAt < 0) skipAt = rows.size();
            }
            day = d;
            list.add(e);
        }
        if (!list.isEmpty()) addDay(list);
        JSONArray skipped = feed.optJSONArray("skipped");
        if (skipped != null && skipped.length() > 0) rows.add(skipAt < 0 ? rows.size() : skipAt, new Row(5, null, null));
        adapter.notifyDataSetChanged();
    }

    /** 一天：标题，最值得看的几件，其余按时间从新到旧。 */
    private void addDay(List<JSONObject> list) {
        rows.add(new Row(0, dayTitle(Theme.millis(list.get(0).optString("time"))), null));
        List<JSONObject> top = top(list);
        if (!top.isEmpty()) rows.add(new Row(4, "最值得看", null));
        for (int i = 0; i < top.size(); i++) rows.add(new Row(1, String.format(Locale.ROOT, "%02d", i + 1), top.get(i)));
        if (!top.isEmpty() && list.size() > top.size()) rows.add(new Row(4, "其余按时间", null));
        for (JSONObject e : list) {
            if (!top.contains(e)) rows.add(new Row(2, null, e));
        }
        list.clear();
    }

    /**
     * 一天里最值得看的：重要性 4 以上、服务器综合分不低于其中第一名的 TOP_RATIO，最多 TOP_MAX 件，有几件算几件。
     * 分数随时间按同样的比例衰减，同一天里谁比谁高多少不会变，前排不会过几个小时自己换人。
     */
    private static List<JSONObject> top(List<JSONObject> day) {
        List<JSONObject> key = new ArrayList<>();
        for (JSONObject e : day) {
            if (e.optInt("importance") >= 4) key.add(e);
        }
        key.sort((a, b) -> Double.compare(b.optDouble("score"), a.optDouble("score")));
        int n = 0;
        while (n < Math.min(key.size(), TOP_MAX)
                && key.get(n).optDouble("score") >= TOP_RATIO * key.get(0).optDouble("score")) n++;
        return key.subList(0, n);
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
        skeletonLoader.stop();
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
            return 6;
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
            if (r.type == 0 || r.type == 4) {
                TextView label = convert != null ? (TextView) convert : r.type == 0 ? sectionLabel() : subLabel();
                label.setText(r.label);
                return label;
            }
            if (r.type == 5) return convert != null ? convert : skippedEntry();
            // 追踪中、最值得看和按时间的行是分段列表：一组的头尾用大圆角
            boolean first = rows.get(i - 1).type != r.type, last = i == rows.size() - 1 || rows.get(i + 1).type != r.type;
            if (r.type == 3) {
                if (convert == null) convert = newFollowRow();
                if (ANSWER.equals(r.label)) bindAnswer((Holder) convert.getTag(), r.e, first, last);
                else bindFollow((Holder) convert.getTag(), r.e, first, last);
                return convert;
            }
            if (convert == null) convert = r.type == 1 ? newTop() : newRow();
            Holder h = (Holder) convert.getTag();
            SwipeDismiss.reset(h.front, h.behind);
            h.front.setBackground(t.segment(t.sheet, first, last));
            float top = first ? Theme.R_XL : Theme.R_XS, bottom = last ? Theme.R_XL : Theme.R_XS;
            h.behind.setBackground(t.round(t.err, top, top, bottom, bottom));
            if (r.type == 1) bindTop(h, r);
            else bindRow(h, r.e);
            h.front.setOnClickListener(v -> open(v, r.e));
            h.front.setOnTouchListener(new SwipeDismiss(h.front, h.behind, v -> dismiss(r.e.optLong("id"))));
            return convert;
        }
    }

    private static final class Holder {
        View front, behind;
        TextView label, badge, num, headline, summary, meta, time;
    }

    /** 每天的标题：大字，是这一天的起点。 */
    private TextView sectionLabel() {
        TextView v = t.text(this, "", 26, t.ink, 900);
        v.setPadding(t.dp(22), t.dp(26), t.dp(22), t.dp(2));
        return v;
    }

    /** 小标题：追踪中、最值得看、其余按时间。 */
    private TextView subLabel() {
        TextView v = t.text(this, "", 13, t.sub, 700);
        v.setPadding(t.dp(24), t.dp(12), t.dp(24), t.dp(8));
        return v;
    }

    /** 可以左滑的一项：后面垫着警示色的“✕ 不需要”。 */
    private FrameLayout swipeFrame(Holder h, View front) {
        FrameLayout frame = new FrameLayout(this);
        TextView behind = t.text(this, "✕  不需要", 15, t.onErr, 800);
        behind.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        behind.setPadding(0, 0, t.dp(26), 0);
        behind.setVisibility(View.INVISIBLE);
        frame.addView(behind, match());
        frame.addView(front, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        h.front = front;
        h.behind = behind;
        frame.setTag(h);
        return frame;
    }

    /** 最值得看的一件：左边是加宽数字的编号，右边是“新”“要闻”、大字标题、两行概要和“时间 · 话题 · 来源”。 */
    private View newTop() {
        Holder h = new Holder();
        LinearLayout item = new LinearLayout(this);
        item.setPadding(t.dp(14), t.dp(16), t.dp(20), t.dp(16));
        item.setClickable(true);

        h.num = t.text(this, "", 26, t.ink, 800);
        h.num.setTypeface(Theme.flex(this, 135, 800));
        h.num.setGravity(Gravity.CENTER_HORIZONTAL);
        h.num.setPadding(0, t.dp(2), 0, 0);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(t.dp(44), ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.rightMargin = t.dp(4);
        item.addView(h.num, nl);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout tags = new LinearLayout(this);
        tags.setGravity(Gravity.CENTER_VERTICAL);
        h.label = newLabel("新");
        LinearLayout.LayoutParams ll = gapLp(6);
        ll.bottomMargin = t.dp(8);
        tags.addView(h.label, ll);
        h.badge = newsBadge();
        LinearLayout.LayoutParams bl = gapLp(0);
        bl.bottomMargin = t.dp(8);
        tags.addView(h.badge, bl);
        col.addView(tags);
        h.headline = t.text(this, "", 18, t.ink, 800);
        h.headline.setLineSpacing(t.dp(4), 1f);
        col.addView(h.headline);
        h.summary = t.text(this, "", 14, t.sub, 400);
        h.summary.setLineSpacing(t.dp(5), 1f);
        h.summary.setMaxLines(2);
        h.summary.setEllipsize(TextUtils.TruncateAt.END);
        h.summary.setPadding(0, t.dp(6), 0, 0);
        col.addView(h.summary);
        h.meta = t.text(this, "", 12.5f, t.sub, 600);
        h.meta.setTypeface(Theme.flex(this, 92, 600));
        h.meta.setSingleLine();
        h.meta.setEllipsize(TextUtils.TruncateAt.END);
        h.meta.setPadding(0, t.dp(9), 0, 0);
        col.addView(h.meta);
        item.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        FrameLayout frame = swipeFrame(h, item);
        frame.setPadding(t.dp(12), t.dp(1.5f), t.dp(12), t.dp(1.5f));
        return frame;
    }

    /** 按时间的一行：左边是时间，离开期间出现的时间用主色；重要性 4 以上的标题加粗、带一行概要。 */
    private View newRow() {
        Holder h = new Holder();
        LinearLayout row = new LinearLayout(this);
        row.setPadding(t.dp(18), t.dp(14), t.dp(18), t.dp(14));
        row.setClickable(true);

        h.time = t.text(this, "", 13, t.sub, 800);
        h.time.setTypeface(Theme.flex(this, 120, 800));
        h.time.setPadding(0, t.dp(2), 0, 0);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(t.dp(42), ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.rightMargin = t.dp(14);
        row.addView(h.time, tl);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        h.badge = newsBadge();
        LinearLayout.LayoutParams bl = gapLp(0);
        bl.bottomMargin = t.dp(6);
        col.addView(h.badge, bl);
        h.headline = t.text(this, "", 15, t.ink, 700);
        h.headline.setLineSpacing(t.dp(4), 1f);
        h.headline.setMaxLines(2);
        h.headline.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(h.headline);
        h.summary = t.text(this, "", 13.5f, t.sub, 400); // 简报概要，不用点开也知道主要内容
        h.summary.setLineSpacing(t.dp(4), 1f);
        h.summary.setSingleLine();
        h.summary.setEllipsize(TextUtils.TruncateAt.END);
        h.summary.setPadding(0, t.dp(3), 0, 0);
        col.addView(h.summary);
        h.meta = t.text(this, "", 12, t.sub, 600);
        h.meta.setTypeface(Theme.flex(this, 92, 600));
        h.meta.setPadding(0, t.dp(5), 0, 0);
        h.meta.setSingleLine();
        h.meta.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(h.meta);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        FrameLayout frame = swipeFrame(h, row);
        frame.setPadding(t.dp(12), t.dp(1.5f), t.dp(12), t.dp(1.5f));
        return frame;
    }

    /** 追踪中：有了答案的问题（主色容器）和追踪的事件（卡片色）放在一组。 */
    private View newFollowRow() {
        Holder h = new Holder();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(t.dp(20), t.dp(16), t.dp(20), t.dp(16));
        h.label = newLabel("");
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.bottomMargin = t.dp(8);
        card.addView(h.label, ll);
        h.headline = t.text(this, "", 15.5f, t.ink, 800);
        h.headline.setLineSpacing(t.dp(4), 1f);
        h.headline.setMaxLines(2);
        h.headline.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(h.headline);
        h.summary = t.text(this, "", 13.5f, t.sub, 400);
        h.summary.setLineSpacing(t.dp(3), 1f);
        h.summary.setMaxLines(2);
        h.summary.setEllipsize(TextUtils.TruncateAt.END);
        h.summary.setPadding(0, t.dp(4), 0, 0);
        card.addView(h.summary);
        h.meta = t.text(this, "", 12.5f, t.sub, 600);
        h.meta.setTypeface(Theme.flex(this, 90, 600));
        h.meta.setPadding(0, t.dp(8), 0, 0);
        card.addView(h.meta);
        h.front = card;
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(t.dp(12), t.dp(1.5f), t.dp(12), t.dp(1.5f));
        frame.addView(card);
        frame.setTag(h);
        return frame;
    }

    private void bindFollow(Holder h, JSONObject f, boolean first, boolean last) {
        h.front.setBackground(t.segment(t.sheet, first, last));
        h.headline.setTextColor(t.ink);
        h.summary.setTextColor(t.sub);
        h.meta.setTextColor(t.sub);
        h.headline.setText(f.optString("headline"));
        String update = f.optString("update");
        show(h.summary, update);
        int n = f.optInt("update_count");
        h.meta.setText((n > 0 ? n + " 条进展，" : "") + "最近 " + Theme.ago(Theme.millis(f.optString("updated"))));
        boolean fresh = n > 0 && seenAtOpen > 0 && Theme.millis(f.optString("updated")) > seenAtOpen;
        show(h.label, fresh ? "新进展" : "");
        long id = f.optLong("id");
        h.front.setOnClickListener(v -> {
            startActivityForResult(new Intent(this, EventActivity.class).putExtra(EventActivity.EXTRA_ID, id), REQ_EVENT);
            overridePendingTransition(R.anim.enter_up, R.anim.hold);
        });
    }

    /** 替你留意的问题有了答案：点开回到当时的对话，答案已经写在里面。 */
    private void bindAnswer(Holder h, JSONObject a, boolean first, boolean last) {
        h.front.setBackground(t.segment(t.pc, first, last));
        h.headline.setTextColor(t.onPc);
        h.summary.setTextColor(t.onPc);
        h.meta.setTextColor(t.onPc);
        show(h.label, "有了答案");
        h.headline.setText("你问过：" + a.optString("question"));
        show(h.summary, a.optString("answer"));
        h.meta.setText(Theme.ago(Theme.millis(a.optString("answered"))));
        Intent chat = new Intent(this, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_THREAD, a.optLong("thread_id"))
                .putExtra(ChatActivity.EXTRA_EVENT, a.optLong("event_id", -1))
                .putExtra(ChatActivity.EXTRA_TITLE, a.optString("question"));
        h.front.setOnClickListener(v -> {
            startActivity(chat);
            overridePendingTransition(R.anim.enter_up, R.anim.hold);
        });
    }

    private void bindTop(Holder h, Row r) {
        JSONObject e = r.e;
        h.num.setText(r.label);
        h.label.setVisibility(isNew(e) ? View.VISIBLE : View.GONE);
        h.badge.setVisibility(e.optInt("importance") >= 5 ? View.VISIBLE : View.GONE);
        h.headline.setText(e.optString("headline"));
        show(h.summary, e.optString("summary"));
        h.meta.setText(meta(clock(Theme.millis(e.optString("time"))) + " · ", e));
    }

    private void bindRow(Holder h, JSONObject e) {
        boolean key = e.optInt("importance") >= 4;
        h.time.setText(clock(Theme.millis(e.optString("time"))));
        h.time.setTextColor(isNew(e) ? t.pri : t.sub);
        h.badge.setVisibility(e.optInt("importance") >= 5 ? View.VISIBLE : View.GONE);
        h.headline.setText(e.optString("headline"));
        h.headline.setTextSize(key ? 15.5f : 15);
        h.headline.setTypeface(Theme.weight(key ? 800 : 500));
        show(h.summary, key ? e.optString("summary") : "");
        h.meta.setText(meta("", e));
    }

    /** “大模型 · IT之家 等 3 家”：话题用墨色写在来源前面，不再上色；prefix 是前面的时间。 */
    private CharSequence meta(String prefix, JSONObject e) {
        SpannableStringBuilder s = new SpannableStringBuilder(prefix);
        String topic = Theme.topicOf(e);
        if (!topic.isEmpty()) s.append(topic, new ForegroundColorSpan(t.ink), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE).append(" · ");
        return s.append(sources(e));
    }

    /** “IT之家 等 3 家”：主要来源和报道的家数。 */
    private static String sources(JSONObject e) {
        JSONArray src = e.optJSONArray("sources");
        String lead = src != null && src.length() > 0 ? src.optString(0) : "";
        int n = e.optInt("source_count");
        return n > 1 ? lead + " 等 " + n + " 家" : lead;
    }

    private static String clock(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(ms));
    }

    /** 信息流按天分组：今天、昨天，更早的写日期和星期。 */
    private static String dayLabel(long ms) {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        long start = today.getTimeInMillis();
        if (ms >= start) return "今天";
        if (ms >= start - 86400_000L) return "昨天";
        return new SimpleDateFormat("M月d日 EEE", Locale.CHINA).format(new Date(ms));
    }

    /** 每天的标题：今天、昨天后面用小字跟上日期。 */
    private CharSequence dayTitle(long ms) {
        String day = dayLabel(ms);
        if (!day.equals("今天") && !day.equals("昨天")) return day;
        String date = new SimpleDateFormat("M月d日 EEE", Locale.CHINA).format(new Date(ms));
        SpannableString s = new SpannableString(day + "  " + date);
        int from = day.length();
        s.setSpan(new AbsoluteSizeSpan(13, true), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(t.sub), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new TypefaceSpan(Theme.flex(this, 100, 650)), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /** 墨色反白的“要闻”：只给重要性 5 的事件。 */
    private TextView newsBadge() {
        TextView v = t.text(this, "要闻", 11.5f, t.onInv, 800);
        v.setPadding(t.dp(8), t.dp(3), t.dp(8), t.dp(3));
        v.setBackground(t.round(t.inv, Theme.R_XS));
        return v;
    }

    /** “未追踪信息”入口：四叶形 + 文字的胶囊，放在第一天的最后，不挡在第一条内容前面。 */
    private View skippedEntry() {
        TextView v = t.text(this, "未追踪信息 ›", 13, t.ink, 600);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(t.dp(10), t.dp(9), t.dp(16), t.dp(9));
        Drawable clover = new Shapes.Blob(Shapes.CLOVER, t.sub);
        clover.setBounds(0, 0, t.dp(22), t.dp(22));
        v.setCompoundDrawablesRelative(clover, null, null, null);
        v.setCompoundDrawablePadding(t.dp(8));
        v.setBackground(t.ripple(t.sheet, 99));
        v.setOnClickListener(x -> showSkipped());
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(t.dp(12), t.dp(10), t.dp(12), t.dp(2));
        frame.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return frame;
    }

    /** 主色小标签：“新”“新进展”“有了答案”。 */
    private TextView newLabel(String text) {
        TextView v = t.text(this, text, 11.5f, t.onPri, 800);
        v.setPadding(t.dp(9), t.dp(3), t.dp(9), t.dp(3));
        v.setBackground(t.round(t.pri, 99));
        return v;
    }

    private LinearLayout.LayoutParams gapLp(int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = t.dp(rightDp);
        return lp;
    }

    private static void show(TextView v, String s) {
        v.setText(s);
        v.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---------- 打开与移除 ----------

    /** 从这一项的位置和大小揭开详情页；详情页头部和列表项都是卡片色，看起来像这一项长大了。 */
    private void open(View from, JSONObject e) {
        Intent i = new Intent(this, EventActivity.class)
                .putExtra(EventActivity.EXTRA_ID, e.optLong("id"))
                .putExtra(EventActivity.EXTRA_JSON, e.toString());
        startActivityForResult(i, REQ_EVENT, ActivityOptions.makeClipRevealAnimation(
                from, 0, 0, from.getWidth(), from.getHeight()).toBundle());
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
        undoCountdown.setVisibility(View.VISIBLE);
        answeredFor = -1;
        for (int i = 0; i < REASONS.length; i++) {
            reasonChips.get(i).setText("source".equals(REASONS[i][0]) && !source.isEmpty()
                    ? "不想看「" + source + "」" : REASONS[i][1]);
        }
        styleChips(reasonChips);
        topicChips.clear();
        topicRow.removeAllViews();
        topicRow.addView(t.text(this, "不想看这类  正在想…", 13, (t.onInv & 0x00FFFFFF) | 0x99000000, 600));
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
        Context app = getApplicationContext();
        new Thread(() -> {
            JSONArray options = null;
            try {
                options = Api.get(app, "/v1/events/" + id + "/reasons").optJSONArray("options");
            } catch (Exception err) {
                Trace.log(app, "reasons.fail", "id=" + id + " " + err);
            }
            JSONArray got = options;
            runOnUiThread(() -> {
                if (isDestroyed() || pendingDismiss != id || answeredFor >= 0) return; // 已经选了或者换了一条
                topicRow.removeAllViews();
                topicChips.clear();
                if (got == null || got.length() == 0) {
                    topicScroll.setVisibility(View.GONE);
                    return;
                }
                TextView label = t.text(this, "不想看这类", 13, (t.onInv & 0x00FFFFFF) | 0x99000000, 600);
                label.setPadding(0, 0, t.dp(8), 0);
                topicRow.addView(label);
                for (int i = 0; i < got.length(); i++) {
                    JSONObject option = got.optJSONObject(i);
                    TextView chip = t.text(this, option.optString("text"), 13, t.onInv, 700);
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
        picked.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
        undoLabel.setText(response);
        undoButton.setVisibility(View.INVISIBLE);
        undoCountdown.stop();
        undoCountdown.setVisibility(View.GONE);
        Trace.log(this, "ui.dismiss.reason", "id=" + pendingDismiss + " " + trace);
        answeredFor = remembers ? pendingDismiss : -1;
        main.postDelayed(hideUndoLater, remembers ? 6000 : 1800); // 等不到服务器回应也会收起
    }

    /** 服务器按原因记下了记忆：在撤销条上说出记住了什么，和对话里的“记住了”一致。 */
    private void showRemembered(long id, JSONObject response) {
        if (isDestroyed() || id != answeredFor || undoBar.getVisibility() != View.VISIBLE) return;
        JSONObject memory = response == null ? null : response.optJSONObject("memory");
        JSONArray added = memory == null ? null : memory.optJSONArray("added");
        undoLabel.setText(added != null && added.length() > 0
                ? "记住了：" + added.optJSONObject(0).optString("text") : "已记下");
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
        Context app = getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject r = Api.post(app, "/v1/events/" + id + "/feedback",
                        new JSONObject().put("value", "not_needed").putOpt("reason", reason).putOpt("topic", topic));
                if (reason != null) runOnUiThread(() -> showRemembered(id, r));
            } catch (Exception err) {
                Trace.log(app, "feedback.fail", "id=" + id + " " + err);
            }
        }, "feedback").start();
    }

    private void hideUndo() {
        if (undoBar.getVisibility() != View.VISIBLE) return;
        undoCountdown.stop();
        undoBar.animate().translationY(t.dp(24)).alpha(0f).setInterpolator(Spring.FX).setDuration(Spring.FX.duration)
                .withEndAction(() -> undoBar.setVisibility(View.GONE)).start();
    }

    // ---------- 菜单 ----------

    private void showMenu() {
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(t.dp(12), 0, t.dp(12), 0);
        Dialog d = t.sheet(this, sheet);
        Drawable memory = new Shapes.Blob(Shapes.SOFT, t.onPc);
        Drawable battery = getDrawable(R.drawable.ic_battery);
        battery.setTint(t.onPc);
        sheet.addView(menuRow(memory, "记忆", "Catchup 记得的关于你的事", () -> {
            d.dismiss();
            startActivity(new Intent(this, MemoryActivity.class));
            overridePendingTransition(R.anim.enter_up, R.anim.hold);
        }));
        sheet.addView(menuRow("+", "添加链接", "从剪贴板", () -> {
            d.dismiss();
            addLinkFromClipboard();
        }));
        sheet.addView(menuRow("↺", "不需要的内容", "可以恢复", () -> {
            d.dismiss();
            showDismissed();
        }));
        sheet.addView(menuRow("↓", "检查更新", "当前 " + Updater.currentVersion(this), () -> {
            d.dismiss();
            checkUpdate(true);
        }));
        sheet.addView(menuRow("i", "诊断信息", "", () -> {
            d.dismiss();
            showDiagnostics();
        }));
        sheet.addView(menuRow(battery, "后台运行设置", "", () -> {
            d.dismiss();
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }));
        t.segments(sheet);
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
                t.segments(items);
                listSheet("不需要的内容", got.length() == 0 ? "最近 30 天没有标记为不需要的内容"
                        : "恢复后会重新出现在首页", items).show();
            });
        }, "dismissed").start();
    }

    /** 底部面板：标题、说明和一个长列表，列表最高占屏幕 3/5，其余滚动。 */
    private Dialog listSheet(String heading, String note, View items) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView title = t.text(this, heading, 22, t.ink, 800);
        title.setPadding(t.dp(24), t.dp(6), t.dp(24), t.dp(4));
        content.addView(title);
        TextView sub = t.text(this, note, 13, t.sub, 400);
        sub.setLineSpacing(t.dp(4), 1f);
        sub.setPadding(t.dp(24), t.dp(4), t.dp(24), t.dp(14));
        content.addView(sub);
        int maxHeight = getResources().getDisplayMetrics().heightPixels * 3 / 5;
        ScrollView scroll = new ScrollView(this) {
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(t.dp(12), 0, t.dp(12), 0);
        scroll.addView(items);
        content.addView(scroll);
        return t.sheet(this, content);
    }

    /** 未追踪信息：热度高但和你无关的事。点开看详情；点“想看这类”当场记成一条长期关注，以后同类的会排到前面。 */
    private void showSkipped() {
        JSONArray list = feed == null ? null : feed.optJSONArray("skipped");
        if (list == null || list.length() == 0) return;
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < list.length(); i++) items.addView(skippedRow(list.optJSONObject(i)));
        t.segments(items);
        listSheet("未追踪信息", "这些不会推送。觉得该看的，点“想看这类”。", items).show();
        Trace.log(this, "ui.skipped", "n=" + list.length());
    }

    private View skippedRow(JSONObject s) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(t.dp(16), t.dp(14), t.dp(16), t.dp(14));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView headline = t.text(this, s.optString("headline"), 14.5f, t.ink, 600);
        headline.setLineSpacing(t.dp(3), 1f);
        headline.setMaxLines(2);
        headline.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(headline);
        TextView meta = t.text(this, s.optInt("outlets") + " 家媒体报道 · " + s.optString("why"), 12, t.sub, 400);
        meta.setPadding(0, t.dp(4), 0, 0);
        text.addView(meta);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView want = t.pill(this, "想看这类", false);
        want.setTextSize(13);
        want.setTypeface(Theme.weight(800));
        want.setPadding(t.dp(13), t.dp(8), t.dp(13), t.dp(8));
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
                    done(want, "✓ 已记下");
                    JSONObject memory = got.optJSONObject("memory");
                    JSONArray added = memory == null ? null : memory.optJSONArray("added");
                    if (added != null && added.length() > 0) {
                        meta.setText("记住了：" + added.optJSONObject(0).optString("text"));
                        meta.setTextColor(t.ink);
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
        row.setPadding(t.dp(16), t.dp(14), t.dp(16), t.dp(14));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView headline = t.text(this, e.optString("headline"), 14.5f, t.ink, 600);
        headline.setLineSpacing(t.dp(3), 1f);
        headline.setMaxLines(2);
        headline.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(headline);
        String reason = e.optString("reason");
        TextView when = t.text(this, "标记于 " + Theme.ago(Theme.millis(e.optString("at")))
                + (reason.isEmpty() ? "" : " · " + reason), 12, t.sub, 400);
        when.setPadding(0, t.dp(4), 0, 0);
        text.addView(when);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView restore = t.pill(this, "恢复", false);
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
                    done(restore, "✓ 已恢复");
                    hidden.remove(id);
                    lastFetch = 0;
                    load();
                });
            }, "restore").start();
            Trace.log(this, "ui.restore", "id=" + id);
        });
        return row;
    }

    /** 菜单的一行：主色容器的小方块里放图标（一个字符或一个图形），右边是名称和说明。 */
    private View menuRow(Object icon, String label, String detail, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(t.dp(16), t.dp(12), t.dp(16), t.dp(12));
        TextView ic = t.text(this, icon instanceof String ? (String) icon : "", 18, t.onPc, 800);
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
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(t.text(this, label, 15, t.ink, 700));
        if (!detail.isEmpty()) {
            TextView d = t.text(this, detail, 12.5f, t.sub, 400);
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

    /** 字标：钴蓝软六边形 + 加宽的 catchup。 */
    private TextView wordmark() {
        TextView v = t.text(this, "catchup", 24, t.ink, 800);
        v.setTypeface(Theme.flex(this, 125, 800));
        v.setLetterSpacing(-0.02f);
        Drawable mark = new Shapes.Blob(Shapes.SOFT, t.pri);
        mark.setBounds(0, 0, t.dp(18), t.dp(18));
        v.setCompoundDrawablesRelative(mark, null, null, null);
        v.setCompoundDrawablePadding(t.dp(6));
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private LinearLayout notice(int color) {
        LinearLayout n = new LinearLayout(this);
        n.setGravity(Gravity.CENTER_VERTICAL);
        n.setPadding(t.dp(20), t.dp(14), t.dp(14), t.dp(14));
        n.setBackground(t.ripple(color, Theme.R_XL));
        return n;
    }

    private View noticeText(String title, String detail) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(t.text(this, title, 14.5f, t.ink, 700));
        TextView d = t.text(this, detail, 12.5f, t.sub, 400);
        d.setPadding(0, t.dp(3), t.dp(8), 0);
        col.addView(d);
        return col;
    }

    private TextView noticeAction(String label, int color, int textColor) {
        TextView a = t.text(this, label, 13, textColor, 800);
        a.setPadding(t.dp(14), t.dp(8), t.dp(14), t.dp(8));
        a.setBackground(t.round(color, 99));
        return a;
    }

    private LinearLayout.LayoutParams noticeLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = t.dp(8);
        return lp;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
