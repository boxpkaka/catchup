package cloud.boxpkaka.infofeed;

import android.app.Application;
import android.util.Log;

/**
 * 进程入口：把未捕获的崩溃连同堆栈写进埋点日志，下次连上服务器时随日志上传，便于远程定位；
 * 记下页面的先后，右滑返回时好让下面那页跟着动（见 Push）；读出上次选的界面风格（见 Style）。
 */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Style.load(this);
        registerActivityLifecycleCallbacks(new Push.Stack());
        Thread.UncaughtExceptionHandler system = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            Trace.log(this, "crash", thread.getName() + " " + Log.getStackTraceString(e).replace('\n', '|'));
            if (system != null) system.uncaughtException(thread, e);
        });
    }
}
