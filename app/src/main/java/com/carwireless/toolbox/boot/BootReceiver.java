package com.carwireless.toolbox.boot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.carwireless.toolbox.App;
import com.carwireless.toolbox.core.Prefs;
import com.carwireless.toolbox.core.ReportLog;

/** 开机广播只负责转发，真正的重试逻辑在 AutoStartService 里（广播接收器活不过 10 秒）。 */
public final class BootReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
            && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        Context app = context.getApplicationContext();
        if (!new Prefs(app).autoHotspot()) return;

        ReportLog log = App.log();
        if (log != null) log.event("BOOT", "收到开机广播，准备自动开热点");
        AutoStartService.start(app);
    }
}
