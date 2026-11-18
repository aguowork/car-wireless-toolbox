package com.carwireless.toolbox.core;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/** 滚动日志 + 报告导出。日志始终在内存里保留最近若干行，界面直接读它。 */
public final class ReportLog {

    private static final int MAX_LINES = 300;
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);

    private final Context context;
    private final Deque<String> lines = new ArrayDeque<String>();
    private String secret = "";

    public ReportLog(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void event(String tag, String message) {
        append("[" + tag + "] " + mask(message));
    }

    public synchronized void error(String tag, String message, Throwable error) {
        append("[" + tag + "] " + mask(message) + "：" + error.getClass().getSimpleName()
            + (error.getMessage() == null ? "" : " " + error.getMessage()));
        StringWriter buffer = new StringWriter();
        error.printStackTrace(new PrintWriter(buffer));
        String[] frames = buffer.toString().split("\n");
        for (int i = 0; i < frames.length && i < 4; i++) append("    " + frames[i].trim());
    }

    /** 记住密码，导出时统一打码。 */
    public synchronized void secret(String value) {
        if (value != null && value.length() >= 8) secret = value;
    }

    private void append(String line) {
        lines.addLast(STAMP.format(new Date()) + "  " + line);
        while (lines.size() > MAX_LINES) lines.removeFirst();
    }

    private String mask(String text) {
        if (text == null) return "";
        if (secret.length() >= 8 && text.contains(secret)) {
            return text.replace(secret, "******");
        }
        return text;
    }

    public synchronized String recentText() {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) builder.append(line).append('\n');
        return builder.length() == 0 ? "（暂无记录）" : builder.toString();
    }

    public String deviceInfo() {
        return "机型：" + Build.MODEL + " / " + Build.BRAND
            + "\nAndroid：" + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）"
            + "\nABI：" + Build.CPU_ABI
            + "\n应用：v" + versionName();
    }

    private String versionName() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable error) {
            return "?";
        }
    }

    public File export(String snapshot) throws Exception {
        File dir = new File(Environment.getExternalStorageDirectory(), "CarWirelessToolbox");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建导出目录：" + dir);
        File file = new File(dir, "report-" + FILE_STAMP.format(new Date()) + ".txt");
        FileOutputStream stream = new FileOutputStream(file);
        OutputStreamWriter writer = new OutputStreamWriter(stream, "UTF-8");
        try {
            writer.write("车机无线助手 · 诊断报告\n");
            writer.write("导出时间：" + new Date().toString() + "\n\n");
            writer.write("===== 设备 =====\n" + deviceInfo() + "\n\n");
            writer.write("===== 状态快照 =====\n" + snapshot + "\n\n");
            writer.write("===== 操作日志 =====\n" + recentText() + "\n");
        } finally {
            writer.close();
        }
        return file;
    }
}
