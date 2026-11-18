package com.carwireless.toolbox.core;

/** 热点名称与密码校验：按 WPA2-PSK 的实际限制来，不按字符数拍脑袋。 */
public final class Validation {

    private Validation() { }

    public static String nameError(String ssid) {
        if (ssid == null || ssid.length() == 0) return "热点名称不能为空";
        int bytes;
        try {
            bytes = ssid.getBytes("UTF-8").length;
        } catch (Exception error) {
            return "热点名称编码异常";
        }
        if (bytes > 32) return "热点名称最多 32 个字节（中文按 3 个算），当前 " + bytes;
        return null;
    }

    public static String passwordError(String password) {
        if (password == null) return "密码不能为空";
        if (password.length() < 8 || password.length() > 63) {
            return "密码需要 8～63 位，当前 " + password.length() + " 位";
        }
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (c < 32 || c > 126) return "密码只能使用英文、数字和常见符号";
        }
        return null;
    }
}
