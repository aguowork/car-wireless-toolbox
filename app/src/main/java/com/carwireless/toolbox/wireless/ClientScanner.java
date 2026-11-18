package com.carwireless.toolbox.wireless;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 热点客户端扫描。
 *
 * <p>4.2.2 没有公开 API 能列出热点客户端，只能从 /proc/net/arp 推断：
 * 取 flags=0x2（完整条目）、MAC 合法、网卡名以 wlan 开头的记录。
 * 主机名额外从 dnsmasq 租约里捞，捞不到就只显示 IP/MAC。</p>
 */
public final class ClientScanner {

    private ClientScanner() { }

    public static List<Snapshot.Client> scan() {
        List<Snapshot.Client> clients = new ArrayList<Snapshot.Client>();
        Map<String, String> hosts = readLeases();

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(new File("/proc/net/arp")));
            String line = reader.readLine(); // 表头
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 6) continue;
                String ip = parts[0];
                String flags = parts[2];
                String mac = parts[3];
                String device = parts[5];
                if (!"0x2".equals(flags)) continue;
                if (mac == null || mac.startsWith("00:00:00")) continue;
                if (device == null || !device.startsWith("wlan")) continue;
                clients.add(new Snapshot.Client(ip, mac, hosts.get(ip)));
            }
        } catch (Throwable ignored) {
            // 读不到就返回空列表，界面会说明原因
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) { }
        }
        return clients;
    }

    private static Map<String, String> readLeases() {
        Map<String, String> hosts = new HashMap<String, String>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(new File("/data/misc/dhcp/dnsmasq.leases")));
            String line;
            // 格式：<expire> <mac> <ip> <hostname> <client-id>
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 4) continue;
                String ip = parts[2];
                String host = parts[3];
                if (host != null && host.length() > 0 && !"*".equals(host)) hosts.put(ip, host);
            }
        } catch (Throwable ignored) {
            // 没有 root 读不到，属于正常情况
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) { }
        }
        return hosts;
    }
}
