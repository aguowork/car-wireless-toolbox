package com.carwireless.toolbox.wireless;

import android.net.wifi.WifiManager;
import android.os.SystemClock;

import com.carwireless.toolbox.core.OperationRunner;
import com.carwireless.toolbox.core.ReportLog;

/** Wi-Fi（STA）开关。同样以实际状态回读为准。 */
public final class WifiController {

    private final WirelessService service;
    private final ReportLog log;

    public WifiController(WirelessService service, ReportLog log) {
        this.service = service;
        this.log = log;
    }

    public OperationRunner.Step switchStep(final boolean enable) {
        return new OperationRunner.Step() {
            @Override public void start(final OperationRunner.Done done) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        done.finish(doSwitch(enable));
                    }
                }, "wifi-switch").start();
            }
        };
    }

    private String doSwitch(boolean enable) {
        WifiManager wifi = service.wifiManager();
        if (wifi == null) return "没有 Wi-Fi 服务";
        int target = enable ? WifiManager.WIFI_STATE_ENABLED : WifiManager.WIFI_STATE_DISABLED;
        try {
            if (wifi.getWifiState() == target) {
                log.event("WIFI", "Wi-Fi 已是目标状态");
                return null;
            }
            // 走 ApBridge 而不是直接 wifi.setWifiEnabled：开机自启那条路也走同一个入口，
            // 两边对「请求被接受」的处理就不会分叉
            if (!ApBridge.setWifiEnabled(wifi, enable)) return "系统拒绝 Wi-Fi 切换请求";
            log.event("WIFI", "已提交" + (enable ? "开启" : "关闭") + "请求，等待实际状态");
            for (int i = 0; i < 28; i++) {
                if (wifi.getWifiState() == target) {
                    log.event("WIFI", "已回读确认 Wi-Fi " + (enable ? "开启" : "关闭"));
                    return null;
                }
                SystemClock.sleep(500);
            }
            return "等待 Wi-Fi 实际状态超时（14 秒）";
        } catch (Throwable error) {
            log.error("WIFI", "切换失败", error);
            return error.getClass().getSimpleName();
        }
    }
}
