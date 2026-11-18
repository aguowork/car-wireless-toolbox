package com.carwireless.toolbox.wireless;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.carwireless.toolbox.core.OperationRunner;
import com.carwireless.toolbox.core.ReportLog;

/**
 * 开热点前把残留的 WLAN 直连组解散掉。
 *
 * <p>为什么还要这一步：这台车机的上网和热点共用 wlan0，只要有一丝 P2P 组残留，
 * wlan0 就被占着，热点起不来。CarLife 车机端后台偶尔会自己去建组，所以每次开热点
 * 前顺手清一下，成本很低。</p>
 *
 * <p>但这一步是<b>尽力而为</b>：P2P 服务不存在、取消协商返回 BUSY、解散失败，
 * 全都不报错，直接往下走。它不该成为开热点的阻碍。</p>
 */
public final class P2pCleaner {

    private static final long SETTLE_MS = 600L;

    /**
     * 兜底放行时间。WifiP2pManager 的回调是主线程消息，框架万一卡住（实测老 ROM 上
     * 偶尔会），这一步就会一直不 finish，然后被 OperationRunner 的 20 秒总超时掐掉——
     * 结果是「清理残留连接」失败，后面的关 Wi-Fi、开热点一步都没跑。
     * 一个尽力而为的步骤不该有这种权力，所以自己定一个更短的闸门。
     */
    private static final long GUARD_MS = 3000L;

    /** P2P 通道建一次就复用，反复 initialize 会一直泄漏 Channel。 */
    private static WifiP2pManager manager;
    private static WifiP2pManager.Channel channel;

    private P2pCleaner() { }

    public static OperationRunner.Step step(final Context context, final ReportLog log) {
        return new OperationRunner.Step() {
            @Override public void start(final OperationRunner.Done done) {
                // 只认第一次收尾：无论是 P2P 回调先到还是闸门先到，另一个就作废
                final boolean[] fired = new boolean[1];
                final OperationRunner.Done once = new OperationRunner.Done() {
                    @Override public void finish(String error) {
                        synchronized (fired) {
                            if (fired[0]) return;
                            fired[0] = true;
                        }
                        done.finish(error);
                    }
                };
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        if (fired[0]) return;
                        log.event("P2P", "清组回调 3 秒未回，不再等待，继续开热点");
                        once.finish(null);
                    }
                }, GUARD_MS);

                if (!prepare(context.getApplicationContext(), log)) { once.finish(null); return; }
                final WifiP2pManager p2p = manager;
                final WifiP2pManager.Channel channel = P2pCleaner.channel;
                try {
                    p2p.cancelConnect(channel, new WifiP2pManager.ActionListener() {
                        @Override public void onSuccess() {
                            removeGroup(p2p, channel, once, log);
                        }
                        @Override public void onFailure(int reason) {
                            // 没有正在进行的协商，这本身就是好事
                            removeGroup(p2p, channel, once, log);
                        }
                    });
                } catch (Throwable error) {
                    log.error("P2P", "取消协商失败，跳过", error);
                    once.finish(null);
                }
            }
        };
    }

    /** 拿到可用的 P2P 通道。本机没有 P2P 服务时返回 false，调用方直接跳过。 */
    private static synchronized boolean prepare(Context app, ReportLog log) {
        if (manager != null && channel != null) return true;
        try {
            manager = (WifiP2pManager) app.getSystemService(Context.WIFI_P2P_SERVICE);
            if (manager == null) return false;
            channel = manager.initialize(app, Looper.getMainLooper(),
                new WifiP2pManager.ChannelListener() {
                    @Override public void onChannelDisconnected() {
                        synchronized (P2pCleaner.class) {
                            channel = null;
                        }
                    }
                });
            return channel != null;
        } catch (Throwable error) {
            log.error("P2P", "初始化 P2P 通道失败，跳过清组", error);
            manager = null;
            channel = null;
            return false;
        }
    }

    private static void removeGroup(final WifiP2pManager p2p, final WifiP2pManager.Channel channel,
                                    final OperationRunner.Done done, final ReportLog log) {
        try {
            p2p.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    log.event("P2P", "已请求解散残留组");
                    // 给驱动一点时间把 wlan0 放出来，再让上层去开热点
                    new Thread(new Runnable() {
                        @Override public void run() {
                            SystemClock.sleep(SETTLE_MS);
                            done.finish(null);
                        }
                    }, "p2p-settle").start();
                }
                @Override public void onFailure(int reason) {
                    // 没有组可解散时这里也会回调，正常
                    log.event("P2P", "解散组返回 " + reason + "，继续");
                    done.finish(null);
                }
            });
        } catch (Throwable error) {
            log.error("P2P", "解散组失败，跳过", error);
            done.finish(null);
        }
    }
}
