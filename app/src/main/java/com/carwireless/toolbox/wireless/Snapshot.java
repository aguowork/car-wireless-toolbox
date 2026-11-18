package com.carwireless.toolbox.wireless;

import java.util.ArrayList;
import java.util.List;

/** 一次完整的状态快照。界面只读这个对象，不直接碰系统接口。 */
public final class Snapshot {

    public int wifiState = -1;
    public int apState = -1;
    public boolean apConfigReadable;
    public String ssid;
    public String password;
    public String apError;
    public String wifiError;

    public List<Client> clients = new ArrayList<Client>();

    public static final class Client {
        public final String ip;
        public final String mac;
        public final String host;

        public Client(String ip, String mac, String host) {
            this.ip = ip;
            this.mac = mac;
            this.host = host;
        }

        public String line() {
            return ip + "　" + mac + (host == null || host.length() == 0 ? "" : "　" + host);
        }
    }

    public static String wifiLabel(int state) {
        switch (state) {
            case 0: return "关闭中";
            case 1: return "已关闭";
            case 2: return "开启中";
            case 3: return "已开启";
            default: return "未知";
        }
    }

    public static String apLabel(int state) {
        switch (state) {
            case ApBridge.AP_DISABLING: return "关闭中";
            case ApBridge.AP_DISABLED: return "已关闭";
            case ApBridge.AP_ENABLING: return "开启中";
            case ApBridge.AP_ENABLED: return "已开启";
            case ApBridge.AP_FAILED: return "开启失败";
            default: return "未知";
        }
    }

    public boolean apOn() { return apState == ApBridge.AP_ENABLED; }

    public static String value(String text) {
        return text == null || text.length() == 0 ? "未知" : text;
    }

    /** 导出报告用。只留对排查热点有用的东西，不堆无关信息。 */
    public String report() {
        StringBuilder text = new StringBuilder();
        text.append("Wi-Fi：").append(wifiLabel(wifiState)).append(optional(wifiError));
        text.append("\n热点：").append(apLabel(apState)).append(optional(apError));
        text.append("\n热点配置可读：").append(apConfigReadable);
        text.append("\n热点名称：").append(value(ssid));
        text.append("\n已连接设备：").append(clients.isEmpty() ? "无" : clients.size() + " 台");
        for (Client client : clients) text.append("\n  ").append(client.line());
        return text.toString();
    }

    private static String optional(String text) {
        return text == null || text.length() == 0 ? "" : "\n" + text;
    }
}
