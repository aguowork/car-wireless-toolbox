package com.carwireless.toolbox.wireless;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;

import com.carwireless.toolbox.core.Reflect;

/** 热点与 Wi-Fi 开关的系统接口封装，供前台界面和开机自启共用。 */
public final class ApBridge {

    /**
     * 热点状态值，全工程唯一的一份定义。
     *
     * <p>这几个数字是 {@code WifiManager} 的隐藏常量（{@code WIFI_AP_STATE_*}），
     * 公开 SDK 里拿不到，只能写字面值。以前 {@code HotspotController} 自己抄了一份
     * 13/14，关热点那一路还直接写了个裸的 11，三处对不上就得逐个排查，所以集中到这里。</p>
     */
    public static final int AP_DISABLING = 10;
    public static final int AP_DISABLED = 11;
    public static final int AP_ENABLING = 12;
    public static final int AP_ENABLED = 13;
    public static final int AP_FAILED = 14;

    private ApBridge() { }

    public static int apState(WifiManager wifi) {
        if (wifi == null) return -1;
        try {
            Object value = Reflect.call(wifi, "getWifiApState", new Class<?>[0]);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable error) {
            return -1;
        }
    }

    public static WifiConfiguration apConfig(WifiManager wifi) {
        if (wifi == null) return null;
        try {
            Object value = Reflect.call(wifi, "getWifiApConfiguration", new Class<?>[0]);
            return value instanceof WifiConfiguration ? (WifiConfiguration) value : null;
        } catch (Throwable error) {
            return null;
        }
    }

    /** 返回系统是否接受了请求。注意：接受 ≠ 真的切换成功，调用方必须回读状态。 */
    public static boolean setApEnabled(WifiManager wifi, WifiConfiguration config, boolean enable) {
        if (wifi == null) return false;
        try {
            java.lang.reflect.Method setter = Reflect.find(WifiManager.class,
                "setWifiApEnabled", WifiConfiguration.class, boolean.class);
            if (setter == null) return false;
            return Boolean.TRUE.equals(setter.invoke(wifi, config, Boolean.valueOf(enable)));
        } catch (Throwable error) {
            return false;
        }
    }

    public static boolean setWifiEnabled(WifiManager wifi, boolean enable) {
        if (wifi == null) return false;
        try {
            return wifi.setWifiEnabled(enable);
        } catch (Throwable error) {
            return false;
        }
    }
}
