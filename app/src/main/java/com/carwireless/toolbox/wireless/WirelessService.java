package com.carwireless.toolbox.wireless;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.carwireless.toolbox.core.Reflect;
import com.carwireless.toolbox.core.ReportLog;

/**
 * 唯一的系统状态源。
 *
 * <p>界面不直接调系统接口，所有状态都从这里读。广播 + 2 秒轮询双保险，
 * 因为老车机的状态广播经常丢。</p>
 *
 * <p>只盯三件事：Wi-Fi 开关、热点开关、热点配置和已连设备。蓝牙和 WLAN 直连
 * 都已经用不上了，不再轮询——每 2 秒少几十次 binder 调用，界面也更跟手。</p>
 */
public final class WirelessService {

    public interface Listener { void onSnapshot(Snapshot snapshot); }

    private static final String ACTION_AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED";
    private static final long POLL_MS = 2000L;

    private final Context context;
    private final ReportLog log;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread ioThread = new HandlerThread("toolbox-io");
    private final Handler io;

    private WifiManager wifi;

    private volatile boolean active;
    private volatile boolean destroyed;
    private boolean refreshing;

    private Snapshot latest = new Snapshot();

    public WirelessService(Context context, ReportLog log, Listener listener) {
        this.context = context.getApplicationContext();
        this.log = log;
        this.listener = listener;
        ioThread.start();
        io = new Handler(ioThread.getLooper());
        try {
            wifi = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
        } catch (Throwable error) {
            log.error("WIFI", "获取 Wi-Fi 服务失败", error);
        }
    }

    // ---------------------------------------------------------------- 生命周期

    public void start() {
        if (active || destroyed) return;
        active = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(ACTION_AP_STATE);
        try {
            context.registerReceiver(receiver, filter);
        } catch (Throwable error) {
            log.error("RECEIVER", "注册状态广播失败", error);
        }
        refresh();
        main.postDelayed(poll, POLL_MS);
    }

    public void stop() {
        if (!active) return;
        active = false;
        main.removeCallbacks(poll);
        try { context.unregisterReceiver(receiver); } catch (Throwable ignored) { }
    }

    public void destroy() {
        stop();
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        io.removeCallbacksAndMessages(null);
        ioThread.quit();
    }

    public Snapshot snapshot() { return latest; }

    public WifiManager wifiManager() { return wifi; }

    // ---------------------------------------------------------------- 轮询

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!active || destroyed) return;
            refresh();
            main.postDelayed(this, POLL_MS);
        }
    };

    public void manualRefresh() { refresh(); }

    // ---------------------------------------------------------------- 广播

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context unused, Intent intent) {
            String action = intent.getAction();
            if (action != null) log.event("STATE", "收到 " + action);
            refresh();
        }
    };

    // ---------------------------------------------------------------- 读取

    public void refresh() {
        if (!active || destroyed || refreshing) return;
        refreshing = true;
        io.post(new Runnable() {
            @Override public void run() {
                final Snapshot snapshot = readSystem();
                main.post(new Runnable() {
                    @Override public void run() {
                        refreshing = false;
                        if (!active || destroyed) return;
                        latest = snapshot;
                        listener.onSnapshot(snapshot);
                    }
                });
            }
        });
    }

    private Snapshot readSystem() {
        Snapshot snapshot = new Snapshot();

        try {
            snapshot.wifiState = wifi == null ? -1 : wifi.getWifiState();
        } catch (Throwable error) {
            snapshot.wifiError = Reflect.describe(error);
        }

        try {
            snapshot.apState = readApState();
        } catch (Throwable error) {
            snapshot.apError = Reflect.describe(error);
        }

        try {
            WifiConfiguration config = readApConfig();
            if (config != null) {
                snapshot.apConfigReadable = true;
                snapshot.ssid = stripQuotes(config.SSID);
                snapshot.password = stripQuotes(config.preSharedKey);
                log.secret(snapshot.password);
            }
        } catch (Throwable error) {
            snapshot.apError = (snapshot.apError == null ? "" : snapshot.apError + "；")
                + "读配置：" + Reflect.describe(error);
        }

        if (snapshot.apOn()) {
            try {
                snapshot.clients = ClientScanner.scan();
            } catch (Throwable error) {
                log.error("CLIENT", "扫描客户端失败", error);
            }
        }
        return snapshot;
    }

    private int readApState() throws Exception {
        Object value = Reflect.call(wifi, "getWifiApState", new Class<?>[0]);
        return value instanceof Integer ? (Integer) value : -1;
    }

    private WifiConfiguration readApConfig() throws Exception {
        Object value = Reflect.call(wifi, "getWifiApConfiguration", new Class<?>[0]);
        return value instanceof WifiConfiguration ? (WifiConfiguration) value : null;
    }

    private static String stripQuotes(String value) {
        if (value == null) return null;
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
