package com.carwireless.toolbox;

import android.app.Application;

import com.carwireless.toolbox.core.ReportLog;

/** 只做两件事：提供全局日志、兜住未捕获异常（车机上崩了也要留下证据）。 */
public final class App extends Application {

    private static ReportLog log;

    @Override public void onCreate() {
        super.onCreate();
        log = new ReportLog(this);
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) {
                log.error("CRASH", "未捕获异常，线程=" + thread.getName(), error);
                if (previous != null) previous.uncaughtException(thread, error);
                else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(1);
                }
            }
        });
    }

    public static ReportLog log() {
        return log;
    }
}
