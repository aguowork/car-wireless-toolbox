package com.carwireless.toolbox.boot;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

import com.carwireless.toolbox.App;
import com.carwireless.toolbox.core.Prefs;
import com.carwireless.toolbox.core.ReportLog;
import com.carwireless.toolbox.wireless.ApBridge;

/**
 * 开机自启的实际执行者：把热点打开。
 *
 * <p>为什么用 Service 而不是在广播接收器里直接做：BOOT_COMPLETED 属于后台广播，
 * 接收器只有约 10 秒；而开机瞬间系统服务往往还没起好，必须留出重试时间。</p>
 *
 * <p>顺序和手动开热点一致：先等 Wi-Fi 彻底关掉，再把 wlan0 交给热点。
 * 这台车机的上网和热点共用一张网卡，顺序反了就起不来。</p>
 */
public final class AutoStartService extends IntentService {

    private static final int ATTEMPTS = 8;
    private static final long INTERVAL_MS = 3000L;

    public AutoStartService() {
        super("auto-start");
    }

    public static void start(Context context) {
        try {
            context.startService(new Intent(context, AutoStartService.class));
        } catch (Throwable error) {
            ReportLog log = App.log();
            if (log != null) log.error("BOOT", "启动开机自启服务失败", error);
        }
    }

    @Override protected void onHandleIntent(Intent intent) {
        ReportLog log = App.log();
        if (!new Prefs(this).autoHotspot()) return;
        if (log != null) log.event("BOOT", "开始执行：开机自动开热点");

        WifiManager wifi;
        try {
            wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        } catch (Throwable error) {
            if (log != null) log.error("BOOT", "获取 Wi-Fi 服务失败", error);
            return;
        }
        if (wifi == null) return;

        boolean done = false;
        for (int attempt = 1; attempt <= ATTEMPTS && !done; attempt++) {
            done = tryHotspot(wifi, log);
            if (!done) SystemClock.sleep(INTERVAL_MS);
        }
        if (log != null) {
            log.event("BOOT", done ? "热点已自动开启" : "重试 " + ATTEMPTS + " 次仍未成功，放弃");
        }
    }

    private boolean tryHotspot(WifiManager wifi, ReportLog log) {
        try {
            if (ApBridge.apState(wifi) == ApBridge.AP_ENABLED) return true;

            // 热点与 Wi-Fi 共用 wlan0，必须先确保 Wi-Fi 已经关干净
            if (wifi.getWifiState() != WifiManager.WIFI_STATE_DISABLED) {
                ApBridge.setWifiEnabled(wifi, false);
                for (int i = 0; i < 10; i++) {
                    if (wifi.getWifiState() == WifiManager.WIFI_STATE_DISABLED) break;
                    SystemClock.sleep(300);
                }
            }

            // 带上系统里存的原配置，保住用户设好的名称和密码
            WifiConfiguration config = ApBridge.apConfig(wifi);
            if (!ApBridge.setApEnabled(wifi, config, true)) {
                ApBridge.setApEnabled(wifi, null, true);
            }
            for (int i = 0; i < 8; i++) {
                if (ApBridge.apState(wifi) == ApBridge.AP_ENABLED) return true;
                SystemClock.sleep(400);
            }
        } catch (Throwable error) {
            if (log != null) log.error("BOOT", "开启热点失败", error);
        }
        return false;
    }
}
