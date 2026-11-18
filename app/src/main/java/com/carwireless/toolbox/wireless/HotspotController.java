package com.carwireless.toolbox.wireless;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

import com.carwireless.toolbox.core.OperationRunner;
import com.carwireless.toolbox.core.Reflect;
import com.carwireless.toolbox.core.ReportLog;
import com.carwireless.toolbox.core.Validation;

import java.util.BitSet;

/**
 * 热点控制。
 *
 * <p>老车机的 setWifiApEnabled 返回值完全不可信：返回 true 也可能没起来。
 * 所以每一步都以 getWifiApState() 的<b>实际状态</b>为准，超时就报失败。</p>
 */
public final class HotspotController {

    private final WirelessService service;
    private final ReportLog log;

    public HotspotController(WirelessService service, ReportLog log) {
        this.service = service;
        this.log = log;
    }

    public int state() {
        return ApBridge.apState(service.wifiManager());
    }

    public OperationRunner.Step switchStep(final boolean enable) {
        return new OperationRunner.Step() {
            @Override public void start(final OperationRunner.Done done) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        String error = doSwitch(enable);
                        done.finish(error);
                    }
                }, "hotspot-switch").start();
            }
        };
    }

    private String doSwitch(boolean enable) {
        WifiManager wifi = service.wifiManager();
        if (wifi == null) return "没有 Wi-Fi 服务";
        int target = enable ? ApBridge.AP_ENABLED : ApBridge.AP_DISABLED;
        try {
            if (state() == target) {
                log.event("HOTSPOT", "热点已经是目标状态，无需操作");
                return null;
            }
            // 先单独确认接口在不在：只有它能区分「ROM 没有这个接口」和「系统拒绝了请求」，
            // 这两种失败给用户的结论完全不同。真正的调用统一走 ApBridge，
            // 免得「反射找方法 + invoke」这套逻辑在工程里存在两份、各改各的。
            if (Reflect.find(WifiManager.class,
                "setWifiApEnabled", WifiConfiguration.class, boolean.class) == null) {
                return "本机 ROM 没有 setWifiApEnabled 接口";
            }

            boolean accepted = false;
            if (enable) {
                // 优先带原有配置启动，避免把厂商的 SSID/密码/频段覆盖成默认值
                try {
                    WifiConfiguration config = readConfig();
                    if (config != null) {
                        log.secret(config.preSharedKey);
                        accepted = ApBridge.setApEnabled(wifi, config, true);
                    }
                } catch (Throwable error) {
                    log.error("HOTSPOT", "带原配置开启失败，改用系统默认配置", error);
                }
            }
            if (!accepted) accepted = ApBridge.setApEnabled(wifi, null, enable);
            log.event("HOTSPOT", "开关请求 accepted=" + accepted + "，目标=" + (enable ? "开启" : "关闭"));
            if (!accepted) return "系统拒绝了热点开关请求";

            for (int i = 0; i < 28; i++) {
                int current = state();
                if (current == target) {
                    log.event("HOTSPOT", "已回读确认热点" + (enable ? "开启" : "关闭"));
                    return null;
                }
                if (current == ApBridge.AP_FAILED) return "热点服务报告失败，本机 ROM 或驱动不支持";
                SystemClock.sleep(500);
            }
            return "等待热点实际状态超时（14 秒），系统可能仍在切换中";
        } catch (Throwable error) {
            log.error("HOTSPOT", "切换热点失败", error);
            return Reflect.describe(error);
        }
    }

    public OperationRunner.Step saveStep(final String ssid, final String password) {
        return new OperationRunner.Step() {
            @Override public void start(final OperationRunner.Done done) {
                String error = Validation.nameError(ssid);
                if (error == null) error = Validation.passwordError(password);
                if (error != null) { done.finish(error); return; }
                log.secret(password);
                new Thread(new Runnable() {
                    @Override public void run() {
                        done.finish(doSave(ssid, password));
                    }
                }, "hotspot-save").start();
            }
        };
    }

    private String doSave(String ssid, String password) {
        WifiManager wifi = service.wifiManager();
        if (wifi == null) return "没有 Wi-Fi 服务";
        try {
            WifiConfiguration config = readConfig();
            if (config == null) {
                return "读不到系统原有热点配置，为避免覆盖厂商设置，已中止保存";
            }
            log.secret(config.preSharedKey);

            config.SSID = ssid;
            config.preSharedKey = password;
            config.hiddenSSID = false;
            if (config.allowedKeyManagement == null) config.allowedKeyManagement = new BitSet();
            config.allowedKeyManagement.clear();
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK);

            java.lang.reflect.Method setter = Reflect.find(WifiManager.class,
                "setWifiApConfiguration", WifiConfiguration.class);
            if (setter == null) return "本机 ROM 没有 setWifiApConfiguration 接口";
            Object value = setter.invoke(wifi, config);
            if (!Boolean.TRUE.equals(value)) return "系统拒绝保存热点配置";

            WifiConfiguration readback = readConfig();
            if (readback == null
                || !ssid.equals(strip(readback.SSID))
                || !password.equals(strip(readback.preSharedKey))) {
                return "请求已提交，但系统回读不一致，可能没有真正保存";
            }
            log.event("HOTSPOT", "配置回读一致，SSID=" + ssid + "（保留系统频段与信道）");
            return null;
        } catch (Throwable error) {
            log.error("HOTSPOT", "保存热点配置失败", error);
            return Reflect.describe(error);
        }
    }

    private WifiConfiguration readConfig() throws Exception {
        WifiManager wifi = service.wifiManager();
        if (wifi == null) throw new IllegalStateException("没有 Wi-Fi 服务");
        return ApBridge.apConfig(wifi);
    }

    private static String strip(String value) {
        if (value == null) return null;
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
