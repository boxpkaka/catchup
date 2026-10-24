package cloud.boxpkaka.infofeed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机或 App 升级后自动恢复连接。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        FeedService.start(context);
    }
}
