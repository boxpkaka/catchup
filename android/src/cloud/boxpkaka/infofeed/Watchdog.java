package cloud.boxpkaka.infofeed;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * 每 15 分钟（系统允许的最短周期）看一眼推送连接：系统在后台把 App 关掉后，连接就断了，
 * 直到下次打开 App 才恢复，期间的推送都收不到。这里把它重新拉起来，重连时会补齐漏掉的消息。
 */
public class Watchdog extends JobService {
    private static final int JOB_ID = 1;

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, Watchdog.class))
                .setPeriodic(15 * 60_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!FeedService.active) {
            Trace.log(this, "watchdog.restart", "status=" + FeedService.status);
            try {
                FeedService.start(this);
            } catch (Exception e) { // Android 12 起后台启动前台服务受限：没关电池优化时会被拒绝
                Trace.log(this, "watchdog.fail", e.toString());
            }
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
