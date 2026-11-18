package com.carwireless.toolbox.core;

import android.os.Handler;
import android.os.Looper;

/**
 * 串行异步步骤执行器。
 *
 * <p>三条铁律，来自老车机的实际教训：</p>
 * <ul>
 *   <li>每一步都必须等到系统的<b>实际状态回读</b>才算完成，不能靠请求返回值；</li>
 *   <li>用 generation 令牌隔离超时：迟到的系统回调不能推进一个已经超时的操作；</li>
 *   <li>步骤可能在 IO 线程结束，Done 一律回主线程派发，界面代码不必自己判断线程。</li>
 * </ul>
 *
 * <p>另外向界面报告当前进行到第几步，多步操作才不会变成"点了没反应"。</p>
 */
public final class OperationRunner {

    public interface Scheduler {
        Object later(Runnable task, long delayMs);
        void cancel(Object token);
    }

    public interface Done { void finish(String error); }
    public interface Step { void start(Done done); }

    public interface Listener {
        /** 某一步开始，index 从 0 开始。 */
        void onStep(int index, int total, String label);
        void finished(String error);
    }

    private final Handler main;
    private final Scheduler scheduler;
    private final long timeoutMs;
    private boolean busy;
    private long generation;
    private Object timer;

    public OperationRunner(Handler main, Scheduler scheduler, long timeoutMs) {
        this.main = main;
        this.scheduler = scheduler;
        this.timeoutMs = timeoutMs;
    }

    public boolean isBusy() { return busy; }

    public boolean run(Step[] steps, Listener listener) {
        return run(steps, null, listener);
    }

    public boolean run(final Step[] steps, final String[] labels, final Listener listener) {
        if (busy) return false;
        busy = true;
        generation++;
        next(steps, labels, 0, listener);
        return true;
    }

    private void next(final Step[] steps, final String[] labels, final int index, final Listener listener) {
        if (index >= steps.length) { complete(null, listener); return; }

        if (listener != null) {
            String label = labels != null && index < labels.length && labels[index] != null
                ? labels[index] : ("第 " + (index + 1) + " 步");
            listener.onStep(index, steps.length, label);
        }

        final long ticket = ++generation;
        timer = scheduler.later(new Runnable() {
            @Override public void run() {
                if (busy && generation == ticket) {
                    complete("第 " + (index + 1) + " 步等待系统状态超时（" + (timeoutMs / 1000) + " 秒）；后续步骤已停止", listener);
                }
            }
        }, timeoutMs);
        try {
            final Done onMain = new Done() {
                @Override public void finish(String error) {
                    if (!busy || generation != ticket) return;
                    scheduler.cancel(timer);
                    timer = null;
                    generation++;
                    if (error != null) complete(error, listener);
                    else next(steps, labels, index + 1, listener);
                }
            };
            // 步骤可能在自己的线程里收尾，这里统一搬回主线程
            Done posted = new Done() {
                @Override public void finish(final String error) {
                    if (Looper.myLooper() == Looper.getMainLooper()) onMain.finish(error);
                    else main.post(new Runnable() {
                        @Override public void run() { onMain.finish(error); }
                    });
                }
            };
            steps[index].start(posted);
        } catch (RuntimeException error) {
            if (busy && generation == ticket) {
                complete("操作异常：" + error.getClass().getSimpleName(), listener);
            }
        }
    }

    private void complete(String error, Listener listener) {
        if (timer != null) scheduler.cancel(timer);
        timer = null;
        busy = false;
        generation++;
        if (listener != null) listener.finished(error);
    }

    public void cancel() {
        if (timer != null) scheduler.cancel(timer);
        timer = null;
        generation++;
        busy = false;
    }
}
