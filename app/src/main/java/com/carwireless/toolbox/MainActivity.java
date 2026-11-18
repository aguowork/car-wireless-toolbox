package com.carwireless.toolbox;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.carwireless.toolbox.core.OperationRunner;
import com.carwireless.toolbox.core.Prefs;
import com.carwireless.toolbox.core.ReportLog;
import com.carwireless.toolbox.core.Validation;
import com.carwireless.toolbox.transfer.FileServer;
import com.carwireless.toolbox.ui.PowerRing;
import com.carwireless.toolbox.ui.Toggle;
import com.carwireless.toolbox.ui.Ui;
import com.carwireless.toolbox.wireless.ApBridge;
import com.carwireless.toolbox.wireless.HotspotController;
import com.carwireless.toolbox.wireless.P2pCleaner;
import com.carwireless.toolbox.wireless.Snapshot;
import com.carwireless.toolbox.wireless.WifiController;
import com.carwireless.toolbox.wireless.WirelessService;

import java.io.File;
import java.util.List;

/**
 * 主界面：三块卡片，没有标题栏，三块平分整屏。
 *
 * <p>各自负责一件事：</p>
 * <ul>
 *   <li><b>左卡</b>：圆环。它既是状态也是按钮——青色=开着，灰色=关着，点一下切换。
 *       底部一行「开机自启」开关，因为它是跟开关有关的设置，不该藏进二级菜单；</li>
 *   <li><b>中卡</b>：上半是热点名称和密码，点了改配置；页脚是已连接设备的数量，
 *       点了看完整列表。两块各弹各的窗，不会串；</li>
 *   <li><b>右卡</b>：文件传输。开启后在热点网段监听 8080，手机浏览器把文件传进来。</li>
 * </ul>
 *
 * <p>去掉标题栏是有意的：车机屏幕只有 600 高，一条标题栏就要吃掉 76，
 * 而它唯一的作用是写一遍用户已经知道的软件名。省下来的高度全部还给内容。</p>
 *
 * <p>操作结果不弹提示条——圆环、状态字、卡片本身已经把结果摆在那儿了。
 * 提示条只留给「为什么失败了」这种界面讲不出来的事。</p>
 */
public final class MainActivity extends Activity implements WirelessService.Listener {

    private static final int CARD_RADIUS = 20;
    private static final int OUTER_PAD = 16;
    private static final int CARD_GAP = 16;
    /**
     * 信息卡页脚高度。86 = 60（第一张卡的「开机自启」行）+ 24（那张卡的下内边距）+ 2（分隔线），
     * 这样两张卡的分隔线落在同一条水平线上，页脚里的字也和「开机自启」齐平。
     */
    private static final int FOOT_HEIGHT = 86;

    private final Handler main = new Handler(Looper.getMainLooper());

    private Ui ui;
    private ReportLog log;
    private Prefs prefs;
    private WirelessService service;
    private OperationRunner runner;
    private WifiController wifiController;
    private HotspotController hotspotController;

    private PowerRing ring;
    private TextView ringState;
    private TextView ringHint;
    private Toggle autoToggle;
    private TextView ssidText;
    private TextView passwordText;
    private TextView countText;
    private TextView exportStatus;
    private TextView toastView;

    private TextView transferButton;
    private TextView transferStatus;
    private TextView transferAddress;
    private TextView transferHint;

    /**
     * 传输卡当前的视觉状态：0=热点没开（按钮禁用）、1=可开启、2=正在服务。
     *
     * <p>这块卡片是每两秒重绘一次的，所以样式只在状态真的变了才套一遍。
     * 以前无条件 setText + setBackground，而 ghostButton/solidButton 每次都新建
     * 一个 Drawable——正好是下面 setValue() 注释里说不能干的事。</p>
     */
    private int transferVisual = -1;
    private Drawable transferGhost;
    private Drawable transferSolid;
    /** 下次重新探测本机 IP 的时间。地址没必要每两秒问一次系统。 */
    private long nextIpProbe;

    /** 设备数那一串字的当前颜色，只在真假变化时改一次。 */
    private int countColorOf = Integer.MIN_VALUE;

    private final Runnable hideToast = new Runnable() {
        @Override public void run() {
            if (toastView == null) return;
            toastView.animate().alpha(0f).setDuration(300L).start();
        }
    };

    private final OperationRunner.Scheduler scheduler = new OperationRunner.Scheduler() {
        @Override public Object later(Runnable task, long delayMs) {
            main.postDelayed(task, delayMs);
            return task;
        }
        @Override public void cancel(Object token) {
            if (token instanceof Runnable) main.removeCallbacks((Runnable) token);
        }
    };

    // ------------------------------------------------------------------ 生命周期

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        log = App.log() == null ? new ReportLog(this) : App.log();
        prefs = new Prefs(this);
        service = new WirelessService(this, log, this);
        runner = new OperationRunner(main, scheduler, 20000L);
        wifiController = new WifiController(service, log);
        hotspotController = new HotspotController(service, log);

        setContentView(buildRoot());
        render(service.snapshot());
    }

    @Override protected void onResume() {
        super.onResume();
        service.start();
        render(service.snapshot());
    }

    @Override protected void onPause() {
        super.onPause();
        service.stop();
    }

    @Override protected void onDestroy() {
        runner.cancel();
        // 界面没了就没人能关它：端口一直占着、文件一直在收，用户还看不见。
        // 传输本来就要求人守在车旁边，界面退出等于这次传输结束了。
        FileServer.get().stop();
        service.destroy();
        main.removeCallbacks(hideToast);
        super.onDestroy();
    }

    @Override public void onSnapshot(Snapshot snapshot) {
        render(snapshot);
    }

    // ------------------------------------------------------------------ 骨架

    private View buildRoot() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        // 三张卡平分整屏：(1024 - 两侧 16 - 两个 16 间隙) / 3 = 320
        LinearLayout row = ui.row();
        row.setBaselineAligned(false);
        row.setPadding(ui.dp(OUTER_PAD), ui.dp(OUTER_PAD), ui.dp(OUTER_PAD), ui.dp(OUTER_PAD));
        row.addView(buildPowerCard(), cardParams(0));
        row.addView(buildInfoCard(), cardParams(1));
        row.addView(buildTransferCard(), cardParams(1));

        root.addView(row, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 提示条浮在三块卡片之上，不占布局
        toastView = ui.text("", 16, Ui.TEXT);
        toastView.setBackground(ui.toastBg());
        toastView.setPadding(ui.dp(26), ui.dp(13), ui.dp(26), ui.dp(13));
        toastView.setGravity(Gravity.CENTER);
        toastView.setAlpha(0f);
        FrameLayout.LayoutParams toastParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toastParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        toastParams.bottomMargin = ui.dp(34);
        root.addView(toastView, toastParams);
        return root;
    }

    private LinearLayout.LayoutParams cardParams(int gaps) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        params.leftMargin = ui.dp(CARD_GAP * gaps);
        return params;
    }

    // ------------------------------------------------------------------ 左卡：圆环

    private View buildPowerCard() {
        LinearLayout card = ui.column();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(ui.dp(32), ui.dp(28), ui.dp(32), ui.dp(24));
        card.setBackground(ui.card(CARD_RADIUS));

        // 上下各一个弹性空格，把「圆环 + 状态 + 提示」这一组顶到卡片中间。
        // 只放下面的弹簧会让圆环贴着顶边，下面空一大块——看起来像没排好，而不是留白。
        card.addView(spring());

        ring = new PowerRing(this);
        ring.setColors(Ui.ACCENT, Ui.RING_IDLE, Ui.SURFACE);
        ring.setContentDescription("热点开关");
        ring.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { onRingTap(); }
        });
        card.addView(ring, new LinearLayout.LayoutParams(ui.dp(200), ui.dp(200)));

        ringState = ui.text("读取中…", 28, Ui.MUTED);
        ringState.setGravity(Gravity.CENTER);
        ringState.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams stateParams = wrap();
        stateParams.topMargin = ui.dp(26);
        card.addView(ringState, stateParams);

        // 圆环能点这件事必须写出来：只写「热点已开启」是陈述句，看不出可以按
        ringHint = ui.text("", 14, Ui.MUTED);
        ringHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = wrap();
        hintParams.topMargin = ui.dp(8);
        card.addView(ringHint, hintParams);

        // 下面那个弹簧：把「开机自启」顶到卡片底部
        card.addView(spring());

        // 分隔线自带「高度 1dp、宽度撑满」的布局参数，不能再传 ui.block() 覆盖掉，
        // 否则高度变成 WRAP_CONTENT，线会消失
        card.addView(ui.divider());

        LinearLayout autoRow = ui.row();
        autoRow.setPadding(0, ui.dp(22), 0, 0);
        autoRow.addView(ui.text("开机自启", 17, Ui.TEXT), new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        autoToggle = new Toggle(this);
        autoToggle.setContentDescription("开机自启");
        autoToggle.setChecked(prefs.autoHotspot(), false);
        autoToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                boolean next = !autoToggle.isChecked();
                autoToggle.setChecked(next, true);
                prefs.setAutoAction(next ? Prefs.AUTO_HOTSPOT : Prefs.AUTO_NONE);
            }
        });
        autoRow.addView(autoToggle, new LinearLayout.LayoutParams(ui.dp(68), ui.dp(38)));
        card.addView(autoRow, ui.block());

        // 「⋯」挂在右上角：三张卡里只有这个角落是空的，放这里不挤占任何信息。
        // 里面是诊断导出和「关于」，出问题时才用。
        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(card, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout more = ui.iconButton(new View.OnClickListener() {
            @Override public void onClick(View view) { showAbout(); }
        });
        more.setContentDescription("关于");
        FrameLayout.LayoutParams moreParams = new FrameLayout.LayoutParams(ui.dp(34), ui.dp(34));
        moreParams.gravity = Gravity.TOP | Gravity.RIGHT;
        moreParams.topMargin = ui.dp(12);
        moreParams.rightMargin = ui.dp(12);
        wrap.addView(more, moreParams);
        return wrap;
    }

    // ------------------------------------------------------------------ 中卡：名称密码 + 设备数

    /**
     * 一张卡分两块，各自可点：上半是名称和密码，点了改配置；页脚是已连接设备的数量，
     * 点了看完整列表。两块各弹各的窗，点哪儿都不会串到另一个。
     *
     * <p>按下反馈只圆自己那一侧的角——贴着卡片边缘的那一边如果也圆，
     * 卡片里就会冒出一条弧线，像嵌了张小卡片。</p>
     *
     * <p>页脚高度写死 {@link #FOOT_HEIGHT}，是为了让这条分隔线和第一张卡
     * 「开机自启」上面那条落在同一水平线上。三张卡并排时两条横线是齐的，
     * 看着才像一套东西。</p>
     */
    private View buildInfoCard() {
        LinearLayout card = ui.column();
        card.setBackground(ui.card(CARD_RADIUS));

        LinearLayout top = ui.column();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(ui.dp(32), ui.dp(32), ui.dp(32), ui.dp(32));
        top.setBackground(ui.pressHighlightTop(CARD_RADIUS));
        top.setContentDescription("修改热点名称与密码");
        top.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showHotspotDialog(); }
        });

        top.addView(ui.text("热点名称", 15, Ui.MUTED), ui.block());

        ssidText = ui.text("—", 36, Ui.TEXT);
        ssidText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        ssidText.setSingleLine(true);
        ssidText.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ssidParams = ui.block();
        ssidParams.topMargin = ui.dp(10);
        top.addView(ssidText, ssidParams);

        LinearLayout.LayoutParams passLabelParams = ui.block();
        passLabelParams.topMargin = ui.dp(26);
        top.addView(ui.text("热点密码", 15, Ui.MUTED), passLabelParams);

        // 密码用等宽字体：0 和 O、1 和 l 一眼能分清，照着往手机里敲不会错
        passwordText = ui.text("—", 27, Ui.TEXT);
        passwordText.setTypeface(Typeface.MONOSPACE);
        passwordText.setSingleLine(true);
        passwordText.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams passParams = ui.block();
        passParams.topMargin = ui.dp(10);
        top.addView(passwordText, passParams);

        // 上半吃掉所有剩余高度，页脚固定 —— 分隔线因此永远在同一个位置
        card.addView(top, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        card.addView(ui.divider());

        LinearLayout foot = ui.row();
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(ui.dp(32), 0, ui.dp(32), 0);
        foot.setBackground(ui.pressHighlightBottom(CARD_RADIUS));
        foot.setContentDescription("查看已连接设备");
        foot.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showDeviceDialog(); }
        });

        foot.addView(ui.text("已连接设备", 15, Ui.MUTED), new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        countText = ui.text("0 台", 24, Ui.ACCENT);
        countText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        foot.addView(countText);

        card.addView(foot, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(FOOT_HEIGHT)));
        return card;
    }

    // ------------------------------------------------------------------ 已连接设备弹窗

    /**
     * 完整设备列表。正面只留一个数字，具体是谁放这里——
     * 卡片高度因此和连接了几台完全无关，多来一台也不会把布局撑变形。
     *
     * <p>取不到主机名的设备（不少手机不报 hostname）用 MAC 顶替，
     * 总比一列「未知设备」有用。</p>
     */
    private void showDeviceDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        List<Snapshot.Client> clients = service.snapshot().clients;

        LinearLayout panel = ui.column();
        panel.setBackground(ui.card(CARD_RADIUS));
        panel.setPadding(ui.dp(40), ui.dp(34), ui.dp(40), ui.dp(28));

        LinearLayout head = ui.row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(heading("已连接设备"), new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(ui.text(clients.size() + " 台", 16, Ui.MUTED));
        panel.addView(head, ui.block());

        LinearLayout rows = ui.column();
        if (clients.isEmpty()) {
            TextView empty = ui.text("暂无设备连接", 17, Ui.FAINT);
            empty.setPadding(0, ui.dp(26), 0, ui.dp(6));
            rows.addView(empty, ui.block());
        } else {
            for (Snapshot.Client client : clients) rows.addView(clientRow(client), ui.block());
        }

        // 最多露 5 行，再多就在框里滚——弹窗不能长到屏幕外面去
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(rows, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int visible = Math.max(1, Math.min(clients.size(), 5));
        panel.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(visible * 58)));

        LinearLayout buttons = buttonRow();
        TextView close = ui.button("关闭", false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { dialog.dismiss(); }
        });
        buttons.addView(close);
        panel.addView(buttons, withTop(20));

        showPanel(dialog, panel, 620);
    }

    /** 一行一台：名字在左（可省略号），IP 在右。和弹窗里的读法一致，从左到右。 */
    private View clientRow(Snapshot.Client client) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, ui.dp(14), 0, ui.dp(14));

        String name = client.host == null || client.host.length() == 0 ? client.mac : client.host;
        TextView label = ui.text(name, 19, Ui.TEXT);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView ip = ui.text(client.ip, 15, Ui.MUTED);
        ip.setTypeface(Typeface.MONOSPACE);
        ip.setSingleLine(true);
        LinearLayout.LayoutParams ipParams = wrap();
        ipParams.leftMargin = ui.dp(16);
        row.addView(ip, ipParams);
        return row;
    }

    // ------------------------------------------------------------------ 第四张卡：文件传输

    /**
     * 开关语义和热点一样：点一下开，再点一下关。
     *
     * <p>开启后在热点网段监听 8080（被占则顺延到 8081/8088/9090），
     * 手机浏览器打开卡上显示的地址就能传文件。关掉立即释放端口。
     * 不做常驻服务——传文件时人就在车旁边，没必要为它占后台。</p>
     *
     * <p>这张卡刻意不列文件清单：车机自带文件管理器，传完去那里看就行，
     * 在这里再列一遍属于重复信息。</p>
     */
    private View buildTransferCard() {
        LinearLayout card = ui.column();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(ui.dp(28), ui.dp(28), ui.dp(28), ui.dp(24));
        card.setBackground(ui.card(CARD_RADIUS));

        LinearLayout head = ui.row();
        head.addView(ui.label("文件传输"), new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        transferStatus = ui.text("已关闭", 14, Ui.MUTED);
        head.addView(transferStatus);
        card.addView(head, ui.block());

        card.addView(spring());

        transferButton = ui.button("开启文件传输", true);
        transferButton.setContentDescription("文件传输开关");
        transferButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { toggleTransfer(); }
        });
        card.addView(transferButton, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(64)));

        transferAddress = ui.text("", 21, Ui.TEXT);
        transferAddress.setTypeface(Typeface.MONOSPACE);
        transferAddress.setGravity(Gravity.CENTER);
        transferAddress.setSingleLine(true);
        LinearLayout.LayoutParams addressParams = ui.block();
        addressParams.topMargin = ui.dp(22);
        card.addView(transferAddress, addressParams);

        transferHint = ui.text("", 13, Ui.MUTED);
        transferHint.setGravity(Gravity.CENTER);
        transferHint.setLineSpacing(ui.dp(4), 1f);
        LinearLayout.LayoutParams hintParams = ui.block();
        hintParams.topMargin = ui.dp(10);
        card.addView(transferHint, hintParams);

        card.addView(spring());
        return card;
    }

    private void toggleTransfer() {
        Snapshot s = service.snapshot();
        if (!s.apOn()) {
            // 热点没开时按钮是禁用的，正常点不到；留着防误触
            toast("请先开启热点");
            syncTransfer(s);
            return;
        }

        FileServer server = FileServer.get();
        if (server.isRunning()) {
            server.stop();
        } else {
            int result = server.start(this);
            if (result == FileServer.START_STORAGE) {
                // 存储不可写和端口被占是两件事，混成一句会把排查带偏
                toast("/sdcard/车机传输 不可写，请检查存储");
            } else if (result == FileServer.START_PORT) {
                toast("开启失败，常用端口都被占用了");
            }
        }
        syncTransfer(s);
    }

    /**
     * 文件传输依附于热点：热点一关，服务必须跟着停。
     *
     * <p>这条约束放在这里、而不是只塞进「关闭热点」那个按钮，是因为热点消失的路子不止一条——
     * 用户在系统设置里打开 Wi-Fi 会把 SoftAP 拆掉（AOSP 4.2.2 的 WifiStateMachine 行为），
     * hostapd 也可能自己挂。只要每两秒的状态回读发现热点没了就把服务收掉，
     * 界面上就永远不会出现「已开启 + 一个谁也连不上的地址」这种假状态。</p>
     */
    private void syncTransfer(Snapshot s) {
        if (transferButton == null) return;

        boolean ap = s.apOn();
        FileServer server = FileServer.get();
        if (!ap && server.isRunning()) server.stop();

        // 上面那句之后，on 一定蕴含 ap
        boolean on = server.isRunning();

        int visual = !ap ? 0 : (on ? 2 : 1);
        if (visual != transferVisual) {
            transferVisual = visual;
            applyTransferVisual(visual);
        }

        String status = on ? "已开启" : "已关闭";
        if (!status.contentEquals(transferStatus.getText())) {
            transferStatus.setText(status);
            transferStatus.setTextColor(on ? Ui.ACCENT : Ui.MUTED);
        }

        if (!on) {
            if (transferAddress.getVisibility() != View.GONE) {
                transferAddress.setText("");
                transferAddress.setVisibility(View.GONE);
            }
            String hint = ap
                ? "开启后手机可通过浏览器\n把文件传进车机"
                : "先开启热点，手机才连得上车机";
            if (!hint.contentEquals(transferHint.getText())) transferHint.setText(hint);
            return;
        }

        // 每两秒枚举一遍网卡纯属浪费，地址只可能随热点开关变化
        long now = System.currentTimeMillis();
        String current = transferAddress.getText().toString();
        if (current.length() == 0 || now >= nextIpProbe) {
            nextIpProbe = now + 10000L;
            String address = FileServer.localIp() + ":" + server.port();
            if (!address.equals(current)) {
                transferAddress.setText(address);
                transferAddress.setTextSize(address.length() > 18 ? 18 : 21);
            }
        }
        if (transferAddress.getVisibility() != View.VISIBLE) {
            transferAddress.setVisibility(View.VISIBLE);
        }

        String hint = "手机浏览器打开上面的地址\n文件保存在 /sdcard/车机传输/";
        if (!hint.contentEquals(transferHint.getText())) {
            transferHint.setText(hint);
            transferHint.setTextColor(Ui.MUTED);
        }
    }

    /** 三种状态的按钮样式。抽出来是因为它现在只在状态变化时被调一次。 */
    private void applyTransferVisual(int visual) {
        if (transferGhost == null) transferGhost = ui.ghostButton(14);
        if (transferSolid == null) transferSolid = ui.solidButton(14);

        transferButton.setEnabled(visual != 0);
        if (visual == 2) {
            transferButton.setText("停止文件传输");
            transferButton.setTextColor(Ui.TEXT);
            transferButton.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            transferButton.setBackground(transferGhost);
        } else if (visual == 1) {
            transferButton.setText("开启文件传输");
            transferButton.setTextColor(Ui.ACCENT_INK);
            transferButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            transferButton.setBackground(transferSolid);
        } else {
            // 热点没开就没什么可传的，直接把按钮禁掉，比点一下弹提示少一步
            transferButton.setText("需先开启热点");
            transferButton.setTextColor(Ui.FAINT);
            transferButton.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            transferButton.setBackground(transferGhost);
        }
    }

    /** 停本地服务是同步动作，没有系统状态要等，直接结束这一步。 */
    private static OperationRunner.Step stopTransferStep() {
        return new OperationRunner.Step() {
            @Override public void start(OperationRunner.Done done) {
                FileServer.get().stop();
                done.finish(null);
            }
        };
    }

    // ------------------------------------------------------------------ 渲染

    private void render(Snapshot s) {
        if (ring == null) return;
        boolean ap = s.apOn();

        if (!runner.isBusy()) {
            ring.setState(ap ? PowerRing.STATE_ON : PowerRing.STATE_OFF);
            if (s.apState == -1) {
                // 还没读到真实状态：先别急着报「已关闭」，那是猜的
                ringState.setText("读取中…");
                ringState.setTextColor(Ui.MUTED);
                ringHint.setText("");
            } else if (s.apState == ApBridge.AP_ENABLING || s.apState == ApBridge.AP_DISABLING) {
                // 系统正在切换。这时候报「已关闭」是错的：热点其实马上就起来了，
                // 而且用户点一下会再发一次开热点请求，AOSP 因为忙直接拒绝，
                // 界面上就蹦出一句莫名其妙的「系统拒绝了热点开关请求」。
                ringState.setText("切换中…");
                ringState.setTextColor(Ui.MUTED);
                ringHint.setText("");
            } else {
                // 状态只留「已开启 / 已关闭」：整屏就这一个开关，不用再点明对象，
                // 「热点」两个字放到提示行里就够了
                ringState.setText(ap ? "已开启" : "已关闭");
                ringState.setTextColor(Ui.TEXT);
                ringHint.setText(ap ? "轻触圆环关闭热点" : "轻触圆环开启热点");
            }
        }

        setValue(ssidText, Snapshot.value(s.ssid), 36, 26);
        setValue(passwordText, passwordOrHint(s.password), 27, 21);

        int connected = s.clients.size();
        String count = connected + " 台";
        if (!count.equals(countText.getText().toString())) countText.setText(count);
        int countColor = connected > 0 ? Ui.ACCENT : Ui.RING_IDLE;
        if (countColor != countColorOf) {
            countColorOf = countColor;
            countText.setTextColor(countColor);
        }

        syncTransfer(s);
    }

    /**
     * 名字和密码都可能很长（SSID 最多 32 字节，密码最多 63 位）。
     * 卡片宽度只有 256dp，按正常字号会溢出，所以超过 10 个字符就降一档字号。
     *
     * <p>这里刻意做了「没变化就不动」的判断：这个方法是每 2 秒被调一次的，
     * 无条件 setText / setTextSize 会让整块卡片每秒重排一次，看起来会抖。</p>
     */
    private static void setValue(TextView view, String text, int normalSp, int smallSp) {
        if (text == null) text = "";
        if (!text.equals(view.getText().toString())) view.setText(text);
        int size = text.length() > 10 ? smallSp : normalSp;
        if ((int) view.getTextSize() != size) view.setTextSize(size);
    }

    /** 密码按用户要求明文显示，读不到时给个能看懂的状态。 */
    private static String passwordOrHint(String password) {
        if (password == null || password.length() == 0) return "未设置";
        return password;
    }

    // ------------------------------------------------------------------ 圆环

    /**
     * 关热点是破坏性动作——按下去手机就断了，CarLife 也要重连。
     * 圆环这么大，误触概率不低，所以关的时候问一句；开的时候直接开。
     */
    private void onRingTap() {
        if (runner.isBusy()) {
            toast("正在切换，请稍候");
            return;
        }
        int state = service.snapshot().apState;
        if (state == ApBridge.AP_ENABLING || state == ApBridge.AP_DISABLING) {
            // 热点正处在系统自己的切换过程中，再发请求只会被拒
            toast("系统正在切换热点，请稍候");
            return;
        }
        if (service.snapshot().apOn()) confirmOff();
        else switchToHotspot();
    }

    private void confirmOff() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = ui.column();
        panel.setBackground(ui.card(CARD_RADIUS));
        panel.setPadding(ui.dp(40), ui.dp(36), ui.dp(40), ui.dp(32));

        panel.addView(heading("关闭热点"), ui.block());

        TextView body = ui.text(
            "手机会断开连接，CarLife 也会断开，重新连上需要几秒。\n\n确定要关闭吗？", 16, Ui.MUTED);
        body.setLineSpacing(ui.dp(6), 1f);
        panel.addView(body, withTop(18));

        LinearLayout buttons = buttonRow();

        TextView cancel = ui.button("取消", false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { dialog.dismiss(); }
        });

        TextView confirm = ui.dangerButton("关闭热点");
        confirm.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                dialog.dismiss();
                // 先收文件服务再关热点：顺序反了的话手机端会对着一个已经没有热点的地址干等，
                // 上传进度卡在最后一格，比直接断掉更难受
                if (FileServer.get().isRunning()) {
                    runSteps(new OperationRunner.Step[] {
                            stopTransferStep(),
                            hotspotController.switchStep(false)
                        },
                        new String[] { "停止文件传输", "关闭热点" });
                } else {
                    runSteps(new OperationRunner.Step[] { hotspotController.switchStep(false) },
                        new String[] { "关闭热点" });
                }
            }
        });

        buttons.addView(cancel);
        buttons.addView(confirm, withLeft(16));
        panel.addView(buttons, ui.block());

        showPanel(dialog, panel, 620);
    }

    private void switchToHotspot() {
        runSteps(new OperationRunner.Step[] {
                P2pCleaner.step(this, log),
                wifiController.switchStep(false),
                hotspotController.switchStep(true)
            },
            // 第一步写「清理残留连接」而不是「清理残留直连组」：直连组是 WLAN 直连的
            // 行话，车上看到的人不知道那是什么，只想知道程序在干嘛
            new String[] { "清理残留连接", "关闭 Wi-Fi", "开启热点" });
    }

    // ------------------------------------------------------------------ 热点配置

    private void showHotspotDialog() {
        Snapshot snapshot = service.snapshot();

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = ui.column();
        panel.setBackground(ui.card(CARD_RADIUS));
        panel.setPadding(ui.dp(40), ui.dp(36), ui.dp(40), ui.dp(32));
        panel.addView(heading("修改热点信息"), ui.block());

        final EditText ssid = ui.input("热点名称");
        ssid.setText(snapshot.ssid == null ? "" : snapshot.ssid);
        ssid.setSelection(ssid.getText().length());
        panel.addView(ui.label("热点名称"), withTop(28));
        panel.addView(ssid, withTop(10));

        final EditText password = ui.input("密码（8～63 位）");
        password.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        password.setText(snapshot.password == null ? "" : snapshot.password);
        password.setSelection(password.getText().length());
        panel.addView(ui.label("密码（明文显示，方便照着输到手机）"), withTop(22));
        panel.addView(password, withTop(10));

        LinearLayout buttons = buttonRow();

        TextView cancel = ui.button("取消", false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { dialog.dismiss(); }
        });

        TextView save = ui.button("保存", true);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                String name = ssid.getText().toString().trim();
                String pass = password.getText().toString();
                String invalid = Validation.nameError(name);
                if (invalid == null) invalid = Validation.passwordError(pass);
                if (invalid != null) { toast(invalid); return; }
                dialog.dismiss();
                saveHotspot(name, pass);
            }
        });

        buttons.addView(cancel);
        buttons.addView(save, withLeft(16));
        panel.addView(buttons, ui.block());

        showPanel(dialog, panel, 660);
    }

    private void saveHotspot(String ssid, String password) {
        final Runnable after = new Runnable() {
            @Override public void run() { render(service.snapshot()); }
        };

        if (service.snapshot().apOn()) {
            // 热点正开着，配置要重启一次热点才会生效
            runSteps(new OperationRunner.Step[] {
                    hotspotController.saveStep(ssid, password),
                    hotspotController.switchStep(false),
                    hotspotController.switchStep(true)
                },
                new String[] { "保存配置", "重启热点", "开启热点" }, after);
        } else {
            runSteps(new OperationRunner.Step[] { hotspotController.saveStep(ssid, password) },
                new String[] { "保存配置" }, after);
        }
    }

    // ------------------------------------------------------------------ 关于

    /**
     * 关于。也是唯一的工具入口——「⋯」点开就是这里，不再先弹一层菜单。
     *
     * <p>导出结果写在弹窗里而不是弹提示条：路径有四十来个字符，提示条 2.6 秒就淡了，
     * 根本看不清；留在框里想抄多久抄多久，失败原因也一并留在原地。</p>
     */
    private void showAbout() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = ui.column();
        panel.setBackground(ui.card(CARD_RADIUS));
        panel.setPadding(ui.dp(40), ui.dp(32), ui.dp(40), ui.dp(28));

        // 不写「关于」小标题：弹窗是从「⋯」点开的，用户知道自己在看什么，
        // 再写一遍是重复信息。应用名本身就是这一屏的主角。
        TextView name = ui.text("车机无线助手", 28, Ui.TEXT);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        panel.addView(name, ui.block());

        panel.addView(ui.divider(22, 0));

        LinearLayout info = ui.column();
        info.setPadding(0, ui.dp(18), 0, ui.dp(18));
        info.addView(infoRow("版本", "v" + versionName()), ui.block());
        info.addView(infoRow("作者", "Guo"), withTop(14));
        info.addView(infoRow("机型", android.os.Build.MODEL + " / " + android.os.Build.BRAND),
            withTop(14));
        info.addView(infoRow("系统", "Android " + android.os.Build.VERSION.RELEASE
            + "（API " + android.os.Build.VERSION.SDK_INT + "）"), withTop(14));
        panel.addView(info, ui.block());

        panel.addView(ui.divider());

        TextView note = ui.text("这台车机的系统设置里没有热点入口，本工具调用系统接口把热点"
            + "开起来，让 CarLife 通过热点连接。", 15, Ui.MUTED);
        note.setLineSpacing(ui.dp(6), 1f);
        panel.addView(note, withTop(20));

        // 导出结果留在这里，不弹提示条
        exportStatus = ui.text("", 14, Ui.ACCENT);
        exportStatus.setLineSpacing(ui.dp(4), 1f);
        exportStatus.setVisibility(View.GONE);
        panel.addView(exportStatus, withTop(16));

        LinearLayout buttons = buttonRow();
        TextView export = ui.button("导出日志", false);
        export.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { exportLog(); }
        });
        TextView ok = ui.button("关闭", true);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { dialog.dismiss(); }
        });
        buttons.addView(export);
        buttons.addView(ok, withLeft(16));
        // buttonRow() 自带 28dp 上间距，这里不再叠一层
        panel.addView(buttons, ui.block());

        showPanel(dialog, panel, 620);
    }

    /** 「标签 + 值」一行。标签固定 64dp 宽，四行的值才能左边缘对齐。 */
    private View infoRow(String label, String value) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(ui.text(label, 15, Ui.MUTED), new LinearLayout.LayoutParams(
            ui.dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView text = ui.text(value, 16, Ui.TEXT);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(text, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /**
     * 导出诊断日志。成功失败都写在弹窗里，不弹提示条。
     * 文件落在 /sdcard/CarWirelessToolbox/，车机的文件管理器能直接找到。
     */
    private void exportLog() {
        if (exportStatus == null) return;
        try {
            File file = log.export(service.snapshot().report());
            exportStatus.setText("已导出到\n" + file.getAbsolutePath());
            exportStatus.setTextColor(Ui.ACCENT);
        } catch (Throwable error) {
            log.error("REPORT", "导出日志失败", error);
            exportStatus.setText("导出失败：" + error.getClass().getSimpleName()
                + "\n多半是外置存储写不进去");
            exportStatus.setTextColor(Ui.DANGER);
        }
        exportStatus.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ 弹窗零件

    private TextView heading(String text) {
        TextView view = ui.text(text, 26, Ui.TEXT);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout buttonRow() {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        row.setPadding(0, ui.dp(28), 0, 0);
        return row;
    }

    /** 弹窗不用系统 Holo 那套皮，自己搭一块圆角面板，跟主界面同一套配色。 */
    /**
     * 显示一个弹窗面板。
     *
     * <p>面板统一套一层 ScrollView，并给窗口高度设上限（屏幕的 92%）。</p>
     *
     * <p>原因是弹窗的实际高度算不准：字体缩放、横屏手机只有 360dp 高、文案换行变多，
     * 任何一项都会让 WRAP_CONTENT 的窗口超出屏幕——而<b>超出的部分会被直接切掉</b>，
     * 窗口自己不会滚。套一层之后：装得下就按内容高度显示，装不下就在框里滚，
     * 底部的按钮永远够得着。</p>
     *
     * <p>键盘的处理放在弹窗这边：主界面是 adjustNothing（三张卡不能被键盘压扁），
     * 所以键盘弹出时必须由弹窗自己让开——整块上移，露出输入框和底部按钮。
     * 必须在 show() 之前设，否则第一次弹出键盘时还按旧模式排版。</p>
     */
    private void showPanel(Dialog dialog, View panel, int widthDp) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        // 窄屏（横屏手机只有 360dp 宽）上 620dp 装不下，按屏幕宽收一收
        int width = Math.min(ui.dp(widthDp), (int) (metrics.widthPixels * 0.92f));
        int maxHeight = (int) (metrics.heightPixels * 0.92f);

        // 先量一遍，才知道内容到底有多高；量出来的高度已被 maxHeight 夹住
        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));

        dialog.setContentView(scroll);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        }
        dialog.show();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        int height = scroll.getMeasuredHeight();
        // 量出 0 说明这一步没成功（极少数机型上会这样），退回让系统自己量，别显示成一条缝
        window.setLayout(width, height > 0 ? height : ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 弹性空格：高度 0 + 权重 1，用来在 LinearLayout 里分配剩余空间。 */
    private View spring() {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return view;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams withTop(int topDp) {
        LinearLayout.LayoutParams params = ui.block();
        params.topMargin = ui.dp(topDp);
        return params;
    }

    private LinearLayout.LayoutParams withLeft(int leftDp) {
        LinearLayout.LayoutParams params = wrap();
        params.leftMargin = ui.dp(leftDp);
        return params;
    }

    // ------------------------------------------------------------------ 步骤执行

    private void runSteps(OperationRunner.Step[] steps, String[] labels) {
        runSteps(steps, labels, null);
    }

    /**
     * 成功不弹提示。
     *
     * <p>原来每步操作完成后都会浮一条「热点已开启」「已停止文件传输」，
     * 但界面上圆环、状态字、传输卡已经把结果摆在那儿了，再浮一条只是把同一句话
     * 说两遍。提示条只留两种用途：<b>说清楚为什么失败了</b>，以及
     * <b>说清楚为什么点了没反应</b>——这两种是界面本身讲不出来的。</p>
     */
    private void runSteps(OperationRunner.Step[] steps, final String[] labels,
                          final Runnable after) {
        if (runner.isBusy()) {
            toast("上一个操作还没结束，请稍候");
            return;
        }
        setBusy(true);
        boolean started = runner.run(steps, labels, new OperationRunner.Listener() {
            @Override public void onStep(int index, int total, String label) {
                showStep(label);
            }
            @Override public void finished(String error) {
                setBusy(false);
                if (error != null) toast(error);
                if (error == null && after != null) after.run();
                service.manualRefresh();
            }
        });
        if (!started) {
            setBusy(false);
            toast("操作未能启动");
        }
    }

    /** 操作过程中把进度直接写在圆环下面，不再单独占一条进度条。 */
    private void showStep(String label) {
        ring.setState(PowerRing.STATE_BUSY);
        ringState.setText(label);
        ringState.setTextColor(Ui.WARN);
        ringHint.setText("");
    }

    private void setBusy(boolean busy) {
        if (ring == null || busy) return;
        boolean ap = service.snapshot().apOn();
        ring.setState(ap ? PowerRing.STATE_ON : PowerRing.STATE_OFF);
        ringState.setText(ap ? "已开启" : "已关闭");
        ringState.setTextColor(Ui.TEXT);
        ringHint.setText(ap ? "轻触圆环关闭热点" : "轻触圆环开启热点");
    }

    // ------------------------------------------------------------------ 提示条

    /** 屏幕底部的浮起提示，2.6 秒后自己淡出。 */
    private void toast(String text) {
        if (toastView == null || text == null || text.length() == 0) return;
        toastView.setText(text);
        toastView.animate().cancel();
        toastView.setAlpha(0f);
        toastView.animate().alpha(1f).setDuration(180L).start();
        main.removeCallbacks(hideToast);
        main.postDelayed(hideToast, 2600L);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable error) {
            return "?";
        }
    }
}
