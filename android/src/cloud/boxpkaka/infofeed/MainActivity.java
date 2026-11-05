package cloud.boxpkaka.infofeed;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
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
 * 首页，分“信息流”“专题”和“报告”三页，左右滑切换（左滑下一页），也可以点标签。
 * 信息流：列表怎么排、点开和右滑见 FeedList，每一种行长什么样见 FeedRows；列表上面是提醒条和记忆的提问，下拉刷新，
 * 滑到底继续加载更早的。移走以后的撤销条见 Undo，菜单和弹出的面板见 Sheets，更新提示见 Updater.Prompt。
 * 专题页见 TopicsView，报告页见 ReportView；推送的周报月报点开直接进报告页（EXTRA_REPORT）。
 */
public class MainActivity extends Activity {
    static final String EXTRA_REPORT = "report"; // 如 weekly/2026-10-19，见 FeedService
    private static final long REFETCH_MS = 30_000;
    private static final long OLDER_RETRY_MS = 10_000;
    private static final String[] TABS = {"信息流", "专题", "报告"};
    private static final int FEED = 0, TOPICS = 1, REPORT = 2;
    static final int REQ_EVENT = 2;

    private Theme t;

    private FrameLayout root;
    private Flow mist, reportMist; // 整页的雾：珍珠银；翻到报告时渐渐换成钴蓝
    private LinearLayout topBar;
    private View topDivider;
    private ListView list;
    private PullLayout pull;
    private LinearLayout header;
    private TextView statusChip;
    static volatile boolean visible; // 首页在前台：补到的旧推送不再弹通知，见 FeedService.notifyIfRecent
    private LinearLayout batteryNotice, notifyNotice, askNotice;
    private TextView askText, askKeep, askDrop;
    private JSONObject ask; // 正在问的那个记忆问题（feed 的 ask），答了就清掉
    private View skeleton;
    private ValueAnimator skeletonGlow;
    private LinearLayout stateView;

    private FeedList feedList;
    private JSONObject feed;
    private long lastFetch;
    private boolean loading, receiving;
    private final Set<Long> hidden = new HashSet<>();
    private Undo undo; // 底部的撤销条：移走一件事以后可以撤销、选原因
    private Sheets sheets; // 菜单和从首页弹出的几个面板
    private Updater.Prompt update; // 顶上的更新提示条
    private Swipe.Tabs tabBar;
    private TopicsView topics;
    private ReportView reports;
    private int tab = FEED;
    private Swipe.Pager homePager;
    private final List<JSONObject> olderEvents = new ArrayList<>(); // 往前翻页加载的，接在第一页后面
    private String olderCursor; // 下一页的 before，到开始采集为止
    private boolean loadingOlder;
    private long olderRetryAt;

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
        new Thread(() -> Images.clearOld(this)).start(); // 换成高清以前缓存的低清图
        feed = Store.loadFeed(this);
        olderCursor = cursor(feed);
        feedList.seenAt(Store.seenAt(this));
        render();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        FeedService.start(this);
        update.onInstallResult(getIntent());
        openReport(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        update.onInstallResult(intent);
        openReport(intent);
    }

    @Override
    public void onBackPressed() {
        if (tab != FEED) homePager.go(FEED);
        else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        // 在详情页点了“不需要”：回到首页后和右滑一样移除并给出撤销
        if (request == REQ_EVENT && result == RESULT_OK && data != null) {
            feedList.dismissRow(data.getLongExtra(EventActivity.EXTRA_DISMISSED, -1));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Trace.log(this, "ui.resume", "service=" + FeedService.status);
        visible = true;
        feedList.seenAt(Store.seenAt(this));
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
        update.check(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Trace.log(this, "ui.pause", "");
        visible = false;
        Track.flush(this);
        if (receiving) {
            unregisterReceiver(receiver);
            receiving = false;
        }
        undo.commit();
    }

    /** 真的离开了（不是上面盖了一张详情页）：看过的事件下次不再算“新的”。放在这里，看完详情回来“新”不会一下子都没了。 */
    @Override
    protected void onStop() {
        super.onStop();
        if (feed != null) Store.setSeenAt(this, System.currentTimeMillis());
    }

    // ---------- 布局 ----------

    private View build() {
        sheets = new Sheets(this, t, panels);
        root = new FrameLayout(this);
        root.setBackgroundColor(t.bg);
        mist = new Flow(this);
        mist.mist(t, Theme.PEARL, 7);
        root.addView(mist, match());
        reportMist = new Flow(this);
        reportMist.mist(t, Theme.PRI, 1103);
        reportMist.setAlpha(0f);
        reportMist.setVisibility(View.INVISIBLE);
        root.addView(reportMist, match());

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, match());

        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(t.dp(20), t.dp(10), t.dp(10), t.dp(6));
        topBar.addView(new Wordmark(this, t), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView ask = t.text(this, "问一问", 13.5f, 0, 700);
        ask.setGravity(Gravity.CENTER);
        t.askButton(ask, t.ask(Theme.PEARL), 99);
        ask.setPadding(t.dp(16), t.dp(9), t.dp(16), t.dp(9));
        ask.setOnClickListener(v -> {
            Push.open(this, new Intent(this, ChatActivity.class));
        });
        topBar.addView(ask);
        TextView menu = t.text(this, "⋮", 20, t.ink, 700);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("菜单");
        menu.setBackground(t.ripple(Color.TRANSPARENT, 99));
        menu.setOnClickListener(v -> sheets.showMenu());
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
        feedList = new FeedList(this, t, list, rowsHost);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView v, int s) {
            }

            @Override
            public void onScroll(AbsListView v, int first, int visible, int total) {
                topDivider.setAlpha(listScrolled() ? 1f : 0f);
                if (total > 0 && first + visible >= total - 4) loadOlder();
            }
        });
        pull = new PullLayout(this, t, list);
        pull.setOnRefresh(() -> {
            load();
            update.check(false);
        });
        homePager = new Swipe.Pager(this, home, true);
        Swipe content = homePager.frame;
        content.addView(pull, match());
        topics = new TopicsView(this, t, this::openTopic);
        topics.setVisibility(View.GONE);
        content.addView(topics, match());
        reports = new ReportView(this, t, this::openEvent);
        reports.setVisibility(View.GONE);
        content.addView(reports, match());
        homePager.jump(tab);
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

        undo = new Undo(this, t, this::undone);
        FrameLayout.LayoutParams ub = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        ub.setMargins(t.dp(16), 0, t.dp(16), t.dp(16));
        root.addView(undo.view(), ub);

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                topBar.setPadding(t.dp(20), bars.top + t.dp(10), t.dp(10), t.dp(6));
                skeleton.setPadding(0, bars.top + t.dp(60), 0, 0);
                list.setPadding(0, 0, 0, bars.bottom + t.dp(96));
                topics.setBottomInset(bars.bottom + t.dp(96));
                reports.setBottomInset(bars.bottom + t.dp(96));
                ub.bottomMargin = bars.bottom + t.dp(16);
                undo.view().setLayoutParams(ub);
                return WindowInsets.CONSUMED;
            });
        }
        return root;
    }

    /** 信息流 / 专题 / 报告：顶栏下面三个胶囊标签，垫着一条磨砂的槽，选中的是墨色金属，左右滑翻页时胶囊跟着手指走。 */
    private View buildTabs() {
        tabBar = new Swipe.Tabs(this, t.inv, t.onInv, t.sub);
        tabBar.finish(t.metal(t.inv, 99), t.frost(99));
        tabBar.setPadding(t.dp(16), t.dp(5), t.dp(16), t.dp(10));
        for (int i = 0; i < TABS.length; i++) {
            int index = i;
            TextView v = t.text(this, TABS[i], 15, t.ink, 800);
            v.setPadding(t.dp(18), t.dp(8), t.dp(18), t.dp(8));
            v.setBackground(t.ripple(Color.TRANSPARENT, 99));
            v.setOnClickListener(x -> homePager.go(index));
            tabBar.addView(v, gapLp(i < TABS.length - 1 ? 6 : 0));
        }
        tabBar.setCount(TABS.length);
        return tabBar;
    }

    /** 三页横向排开，左右滑时相邻的一页跟着手指进来，胶囊标签一起走；两头像橡皮筋。 */
    private final Swipe.Pager.Host home = new Swipe.Pager.Host() {
        @Override
        public int count() {
            return TABS.length;
        }

        @Override
        public View page(int which) {
            if (which == TOPICS) topics.refreshIfStale();
            if (which == REPORT) reports.showIfEmpty();
            return which == FEED ? pull : which == TOPICS ? topics : reports;
        }

        @Override
        public void moved(float position) {
            tabBar.at(position);
            float report = Math.max(0, Math.min(1, position - REPORT + 1)); // 从专题翻到报告的路上，雾渐渐换成钴蓝
            reportMist.setAlpha(report);
            reportMist.setVisibility(report > 0 ? View.VISIBLE : View.INVISIBLE);
            mist.setVisibility(report < 1 ? View.VISIBLE : View.INVISIBLE);
        }

        @Override
        public void settled(int which) {
            if (which != tab) Trace.log(MainActivity.this, "ui.tab", which == TOPICS ? "topics" : which == REPORT ? "report" : "feed");
            tab = which;
            topDivider.setAlpha(which == FEED && listScrolled() ? 1f : 0f);
        }
    };

    /** 菜单和面板里的操作改了首页该显示什么：马上重新取。 */
    private final Sheets.Host panels = new Sheets.Host() {
        @Override
        public void reload() {
            lastFetch = 0;
            load();
        }

        @Override
        public void restored(long id) {
            hidden.remove(id);
            reload();
        }

        @Override
        public void checkUpdate() {
            update.check(true);
        }
    };

    /** 列表里的操作要改首页的数据：移走、取消追踪、看未追踪信息。 */
    private final FeedList.Host rowsHost = new FeedList.Host() {
        @Override
        public boolean showing() {
            return visible && tab == FEED;
        }

        @Override
        public void dismissed(long id) {
            dismiss(id);
        }

        @Override
        public void left(boolean answer, long id) {
            JSONArray rows = feed == null ? null : feed.optJSONArray(answer ? "answers" : "following");
            for (int i = 0; rows != null && i < rows.length(); i++) {
                if (rows.optJSONObject(i).optLong("id") == id) {
                    rows.remove(i);
                    break;
                }
            }
            render();
        }

        @Override
        public void skipped() {
            sheets.showSkipped(feed == null ? null : feed.optJSONArray("skipped"));
        }
    };

    /** 信息流往上滚过了：顶栏下面显示分隔线。 */
    private boolean listScrolled() {
        return list.getFirstVisiblePosition() > 0 || (list.getChildCount() > 0 && list.getChildAt(0).getTop() < 0);
    }

    /** 推送的周报、月报点开：进报告页并显示那一份。 */
    private void openReport(Intent intent) {
        String r = intent == null ? null : intent.getStringExtra(EXTRA_REPORT);
        if (r == null) return;
        intent.removeExtra(EXTRA_REPORT); // 转屏重建时不再重复打开
        String[] parts = r.split("/", 2);
        homePager.jump(REPORT);
        reports.show(parts[0], parts.length > 1 ? parts[1] : null);
    }

    /** 专题卡片原地展开成专题页。 */
    private void openTopic(View from, JSONObject topic) {
        Card.open(this, from, new Intent(this, TopicActivity.class)
                .putExtra(TopicActivity.EXTRA_KEY, topic.optString("key"))
                .putExtra(TopicActivity.EXTRA_TITLE, topic.optString("title")));
    }

    /** 报告里的一条展开成它说的那件事。 */
    private void openEvent(View from, long id) {
        Card.open(this, from, new Intent(this, EventActivity.class).putExtra(EventActivity.EXTRA_ID, id)
                .putExtra(EventActivity.EXTRA_FROM, "report"), REQ_EVENT);
    }

    private View buildHeader() {
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(t.dp(12), t.dp(4), t.dp(12), 0);

        // 提醒条：更新用反色，最显眼；其余用卡片色，右边是主色容器的按钮
        LinearLayout updateNotice = notice(t.inv);
        TextView updateText = t.text(this, "", 14.5f, t.onInv, 700);
        updateNotice.addView(updateText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        int[] ask = t.ask(Theme.PEARL);
        TextView updateAction = noticeAction("更新", ask[0], ask[1]);
        updateAction.setBackground(t.metal(ask[0], 99));
        updateNotice.addView(updateAction);
        updateNotice.setVisibility(View.GONE);
        header.addView(updateNotice, noticeLp());
        update = new Updater.Prompt(this, updateNotice, updateText, updateAction);

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

        // 记忆的提问：“最近 6 条「语音合成」的内容你都没点开，还关注吗？”，答了就收起（见 personal.ask）
        askNotice = new LinearLayout(this);
        askNotice.setOrientation(LinearLayout.VERTICAL);
        askNotice.setPadding(t.dp(20), t.dp(14), t.dp(10), t.dp(16));
        askNotice.setBackground(t.frost(Theme.R_XL));
        LinearLayout askTop = new LinearLayout(this);
        askTop.setGravity(Gravity.CENTER_VERTICAL);
        askTop.addView(t.text(this, "想跟你确认一下", 12, t.sub, 700),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView later = t.text(this, "以后再说", 12, t.sub, 700);
        later.setPadding(t.dp(10), t.dp(6), t.dp(10), t.dp(6));
        later.setBackground(t.ripple(Color.TRANSPARENT, 99));
        later.setOnClickListener(v -> answerAsk("later"));
        askTop.addView(later);
        askNotice.addView(askTop);
        askText = t.text(this, "", 15, t.ink, 700);
        askText.setLineSpacing(t.dp(3), 1f);
        askText.setPadding(0, t.dp(2), t.dp(10), 0);
        askNotice.addView(askText);
        LinearLayout askButtons = new LinearLayout(this);
        askButtons.setPadding(0, t.dp(12), 0, 0);
        askKeep = noticeAction("", t.pc, t.onPc);
        askKeep.setOnClickListener(v -> answerAsk("keep"));
        askButtons.addView(askKeep);
        askDrop = noticeAction("", t.bg, t.ink);
        askDrop.setOnClickListener(v -> answerAsk("drop"));
        LinearLayout.LayoutParams dropLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dropLp.leftMargin = t.dp(8);
        askButtons.addView(askDrop, dropLp);
        askNotice.addView(askButtons);
        askNotice.setVisibility(View.GONE);
        header.addView(askNotice, noticeLp());

        statusChip = t.text(this, "", 12.5f, t.onErr, 600);
        statusChip.setPadding(t.dp(10), t.dp(5), t.dp(10), t.dp(5));
        statusChip.setBackground(t.round(t.err, Theme.R_S));
        statusChip.setVisibility(View.GONE);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.topMargin = t.dp(8);
        header.addView(statusChip, chipLp);
        return header;
    }

    /** 首次加载的骨架屏：形状和真实布局一致（追踪中一组、日期、最值得看），一块接一块慢慢明暗起伏。 */
    private View buildSkeleton() {
        FrameLayout s = new FrameLayout(this);
        s.setPadding(0, t.dp(60), 0, 0);
        LinearLayout blocks = new LinearLayout(this);
        blocks.setOrientation(LinearLayout.VERTICAL);
        blocks.addView(block(-1, 136, Theme.R_XL, 36));
        blocks.addView(block(96, 32, Theme.R_S, 30));
        blocks.addView(block(-1, 320, Theme.R_XL, 44));
        blocks.addView(block(-1, 180, Theme.R_XL, 10));
        s.addView(blocks, match());
        skeletonGlow = Shapes.breathe(blocks);
        return s;
    }

    private View block(int widthDp, int heightDp, float radiusDp, int topDp) {
        View v = new View(this);
        v.setBackground(t.frost(radiusDp));
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
                    if (undo.pending() >= 0) hidden.add(undo.pending());
                    updateStatusChip(); // 清掉“刷新失败”，换回推送断开或服务端疑似故障的提示
                    showAsk(got.optJSONObject("ask"));
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
            showState("?", "这台手机\n还没被确认", "有密钥的话填在下面，点“重新检查”就能用（只能看，不能改）。"
                    + "没有密钥的话，在服务器上运行下面的命令，再回来点“重新检查”。",
                    "python3 -m catchup devices approve " + (pendingDevice >= 0 ? pendingDevice : "<设备编号>"), true);
            return;
        }
        if (feed == null) {
            showState("!", "连不上服务器", "检查网络后重试。\n" + reason(e), null, false);
        } else {
            showStatus("刷新失败：" + reason(e));
        }
    }

    private void showState(String mark, String title, String body, String code, boolean askKey) {
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
        EditText key = null;
        if (askKey) {
            key = new EditText(this);
            key.setHint("密钥");
            key.setHintTextColor(t.sub);
            key.setTextColor(t.ink);
            key.setTextSize(15);
            key.setSingleLine(true);
            key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            key.setPadding(t.dp(16), t.dp(14), t.dp(16), t.dp(14));
            key.setBackground(t.round(t.sheet, Theme.R_M));
            LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            kl.topMargin = t.dp(14);
            stateView.addView(key, kl);
        }
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
        EditText typed = key;
        retry.setOnClickListener(v -> {
            String k = typed == null ? "" : typed.getText().toString().trim();
            if (!k.isEmpty()) {
                useKey(k);
                return;
            }
            stateView.setVisibility(View.GONE);
            load();
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(56));
        rl.topMargin = t.dp(28);
        stateView.addView(retry, rl);
        stateView.setVisibility(View.VISIBLE);
    }

    /** 用分享密钥把这台手机批准为只读设备，成功后重新加载。 */
    private void useKey(String key) {
        Context app = getApplicationContext();
        new Thread(() -> {
            String err = null;
            try {
                Api.post(app, "/v1/key", new JSONObject().put("key", key));
            } catch (Exception e) {
                err = reason(e);
                Trace.log(app, "key.fail", e.toString());
            }
            String error = err;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (error != null) {
                    toast(error);
                    return;
                }
                stateView.setVisibility(View.GONE);
                load();
            });
        }, "key").start();
    }

    private void render() {
        if (feed == null) return;
        stopSkeleton();
        stateView.setVisibility(View.GONE);
        feedList.render(feed, olderEvents, hidden);
    }

    /** 整个 App 的通知或“新消息”分类被关掉了；分类还没建（服务没启动过）时不算。 */
    private boolean notificationsBlocked() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = nm.getNotificationChannel(FeedService.CHANNEL_MESSAGES);
        return !nm.areNotificationsEnabled() || ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE;
    }

    private void stopSkeleton() {
        if (skeleton.getVisibility() == View.GONE) return;
        skeleton.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            skeletonGlow.cancel();
            skeleton.setVisibility(View.GONE);
        }).start();
    }

    /** 推送连接断开时说断开，否则显示服务端判断的疑似故障（feed 的 health，正常时为空）。 */
    private void updateStatusChip() {
        boolean bad = FeedService.status.startsWith("连接断开");
        showStatus(bad ? "推送连接断开，正在重试" : feed == null ? "" : feed.optString("health"));
    }

    /** 首页顶部的小红条，空字符串时收起；出现时淡入。 */
    private void showStatus(String s) {
        statusChip.setText(s);
        if (s.isEmpty()) {
            statusChip.setVisibility(View.GONE);
        } else if (statusChip.getVisibility() != View.VISIBLE) {
            statusChip.setAlpha(0f);
            statusChip.setVisibility(View.VISIBLE);
            statusChip.animate().alpha(1f).setDuration(200).start();
        }
    }

    // ---------- 记忆的提问 ----------

    /** 服务器挂着的问题（没有时为 null）：换了问题才重新淡入，答过的就收起。 */
    private void showAsk(JSONObject a) {
        if (a == null) {
            ask = null;
            askNotice.setVisibility(View.GONE);
            return;
        }
        if (ask != null && ask.optLong("id") == a.optLong("id")) return;
        ask = a;
        askText.setText(a.optString("text"));
        askKeep.setText(a.optString("keep"));
        askDrop.setText(a.optString("drop"));
        askNotice.setAlpha(0f);
        askNotice.setVisibility(View.VISIBLE);
        askNotice.animate().alpha(1f).setDuration(240).start();
        Trace.log(this, "ui.ask", "id=" + a.optLong("id"));
    }

    /** keep：还关注（加强这条记忆）；drop：不关注了（删掉）；later：以后再说（一个月内不再问这条）。 */
    private void answerAsk(String answer) {
        JSONObject a = ask;
        if (a == null) return;
        ask = null;
        try {
            feed.put("ask", JSONObject.NULL);
        } catch (Exception ignored) {
            // 键不为空，不会发生
        }
        askNotice.animate().alpha(0f).translationY(-t.dp(8)).setDuration(180).withEndAction(() -> {
            askNotice.setVisibility(View.GONE);
            askNotice.setTranslationY(0f);
        }).start();
        long id = a.optLong("id");
        boolean task = "task".equals(a.optString("kind"));
        if ("keep".equals(answer)) toast(task ? "好的，继续帮你留意这件事" : "好的，我会继续替你留意");
        else if ("drop".equals(answer)) toast(task ? "好的，这件事告一段落，不再优先推了" : "好的，以后少打扰你这类内容");
        Context app = getApplicationContext();
        new Thread(() -> {
            try {
                Api.post(app, "/v1/asks/" + id, new JSONObject().put("answer", answer));
            } catch (Exception err) {
                Trace.log(app, "ask.fail", "id=" + id + " " + err);
                return;
            }
            if ("drop".equals(answer)) runOnUiThread(() -> {
                lastFetch = 0;
                load(); // 记忆删了，首页按新记忆重新排
            });
        }, "ask").start();
        Trace.log(this, "ui.ask.answer", "id=" + id + " " + answer);
    }

    // ---------- 移走与撤销 ----------

    /** 从列表里移走，底部升起撤销条（见 Undo），倒数走完才提交给服务器。 */
    private void dismiss(long id) {
        if (id < 0) return;
        JSONObject e = feedList.findEvent(id); // 先取，render() 之后列表里就没有它了
        undo.commit(); // 上一条还在撤销期内的，先提交
        hidden.add(id);
        render();
        undo.show(id, e == null ? "" : e.optString("source"));
    }

    /** 撤销条上点了撤销：这一行放回来，从无到有展开。 */
    private void undone(long id) {
        if (id >= 0) hidden.remove(id);
        feedList.expand(id);
        render();
    }

    // ---------- 小工具 ----------

    static String clock(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(ms));
    }

    /** 信息流按天分组：今天、昨天，更早的写日期和星期。 */
    static String dayLabel(long ms) {
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

    /** 每天的标题：今天、昨天后面用小字跟上日期。专题页也用。 */
    static CharSequence dayTitle(Context c, Theme t, long ms) {
        String day = dayLabel(ms);
        if (!day.equals("今天") && !day.equals("昨天")) return day;
        String date = new SimpleDateFormat("M月d日 EEE", Locale.CHINA).format(new Date(ms));
        SpannableString s = new SpannableString(day + "  " + date);
        int from = day.length();
        s.setSpan(new AbsoluteSizeSpan(13, true), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(t.sub), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new TypefaceSpan(Theme.flex(c, 100, 650)), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private LinearLayout.LayoutParams gapLp(int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = t.dp(rightDp);
        return lp;
    }

    static String reason(Exception e) {
        if (e instanceof java.net.UnknownHostException) return "无法解析服务器地址";
        if (e instanceof java.net.SocketTimeoutException) return "连接超时";
        if (e instanceof java.net.ConnectException) return "无法访问服务器";
        if (e instanceof javax.net.ssl.SSLException) return "安全连接失败";
        return String.valueOf(e == null ? "未知错误" : e.getMessage());
    }

    private LinearLayout notice(int color) {
        LinearLayout n = new LinearLayout(this);
        n.setGravity(Gravity.CENTER_VERTICAL);
        n.setPadding(t.dp(20), t.dp(14), t.dp(14), t.dp(14));
        n.setBackground(color == t.sheet ? t.frostRipple(t.frost, Theme.R_XL) // 提醒条：磨砂；更新是墨色金属
                : new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22FFFFFF),
                t.metal(color, Theme.R_XL), t.round(Color.WHITE, Theme.R_XL)));
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
