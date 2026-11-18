package com.carwireless.toolbox.core;

import android.content.Context;
import android.content.SharedPreferences;

/** 配置持久化。开机自启读这里。 */
public final class Prefs {

    public static final int AUTO_NONE = 0;
    public static final int AUTO_HOTSPOT = 1;

    private static final String FILE = "toolbox";
    private static final String KEY_AUTO = "auto_action";

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int autoAction() { return prefs.getInt(KEY_AUTO, AUTO_NONE); }

    public void setAutoAction(int value) { prefs.edit().putInt(KEY_AUTO, value).apply(); }

    public boolean autoHotspot() { return autoAction() == AUTO_HOTSPOT; }
}
