package com.carwireless.toolbox.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 界面构件与配色。
 *
 * <p>车机是 1024×600、密度 160，也就是 1024dp × 600dp——比手机宽得多、也矮一些。
 * 所以这里的取向是：<b>字号敢放大</b>、横排而不是竖堆、主操作只留一个。</p>
 *
 * <p>配色只分三层：底色、卡片、悬浮层。强调色只有一个青，红色只留给破坏性动作。
 * 所有可点的东西都有按下反馈——没有反馈的按钮在车上会让人以为没点到。</p>
 *
 * <p>注意：刻意不用 setLetterSpacing / setElevation 这类 API 21 才有的方法，
 * 车机是 4.2.2（API 17），调用了会直接崩。</p>
 */
public final class Ui {

    public static final int BG = Color.rgb(11, 15, 20);
    public static final int SURFACE = Color.rgb(21, 27, 34);
    public static final int SURFACE_HI = Color.rgb(29, 37, 46);
    public static final int STROKE = Color.rgb(38, 48, 57);
    public static final int ACCENT = Color.rgb(45, 212, 191);
    public static final int ACCENT_INK = Color.rgb(4, 30, 27);
    /** 关闭态圆环的颜色：比描边亮一档，暗底上还看得见轮廓。 */
    public static final int RING_IDLE = Color.rgb(58, 71, 83);
    public static final int TEXT = Color.rgb(242, 246, 250);
    public static final int MUTED = Color.rgb(122, 138, 154);
    /** 比 MUTED 再暗一档，给「暂无内容」这类不该被看见的字。 */
    public static final int FAINT = Color.rgb(94, 109, 124);
    public static final int DANGER = Color.rgb(248, 113, 113);
    public static final int WARN = Color.rgb(251, 191, 36);

    private final Context context;

    public Ui(Context context) {
        this.context = context;
    }

    // ------------------------------------------------------------------ 容器

    public LinearLayout column() {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    public LinearLayout row() {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    // ------------------------------------------------------------------ 文字

    public TextView text(String label, int size, int color) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    /** 信息块上方的小标签：小、灰、不抢内容。 */
    public TextView label(String text) {
        return text(text, 13, MUTED);
    }

    // ------------------------------------------------------------------ 可点构件

    /** 圆形图标按钮。 */
    public LinearLayout iconButton(View.OnClickListener action) {
        LinearLayout box = new LinearLayout(context);
        box.setGravity(Gravity.CENTER);
        box.setBackground(pressable(Color.TRANSPARENT, SURFACE_HI, Color.TRANSPARENT, 22));

        IconView icon = new IconView(context, MUTED);
        box.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        box.setOnClickListener(action);
        return box;
    }

    /** 弹窗里的按钮：主要动作用青底深字，次要动作用描边。 */
    public TextView button(String label, boolean primary) {
        TextView view = text(label, 19, primary ? ACCENT_INK : TEXT);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(34), dp(16), dp(34), dp(16));
        if (primary) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setBackground(primary ? solidButton(14) : ghostButton(14));
        return view;
    }

    /** 青底按钮的背景。单独暴露出来，是为了让卡片上的按钮能在运行时切换样式。 */
    public Drawable solidButton(int radiusDp) {
        return pressableFill(ACCENT, blend(ACCENT, TEXT, 0.18f), radiusDp);
    }

    /** 描边按钮的背景，用于「可撤销」的次要动作。 */
    public Drawable ghostButton(int radiusDp) {
        return pressable(Color.TRANSPARENT, SURFACE_HI, STROKE, radiusDp);
    }

    /** 破坏性动作的按钮：红底深字。只给「关闭热点」这类会断开连接的确认用。 */
    public TextView dangerButton(String label) {
        TextView view = text(label, 19, Color.rgb(43, 9, 9));
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(34), dp(16), dp(34), dp(16));
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setBackground(pressableFill(DANGER, blend(DANGER, Color.BLACK, 0.16f), 14));
        return view;
    }

    /** 弹窗里的输入框：深底细边，不带系统那两条下划线。 */
    public EditText input(String hintText) {
        EditText view = new EditText(context);
        view.setSingleLine(true);
        view.setTextColor(TEXT);
        view.setHintTextColor(FAINT);
        view.setHint(hintText);
        view.setTextSize(22);
        view.setPadding(dp(18), dp(15), dp(18), dp(15));
        view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        view.setBackground(field(12));
        return view;
    }

    public View divider() {
        View view = new View(context);
        view.setBackgroundColor(STROKE);
        view.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(2))));
        return view;
    }

    /**
     * 带上下间距的分隔线。
     *
     * <p>间距是<b>改它自己的布局参数</b>，不是另给一套——分隔线的 2dp 高度必须留住。
     * 一旦用 addView(view, params) 换成 WRAP_CONTENT，一个空的 View 会按
     * {@code View.getDefaultSize} 的规则把父容器剩下的高度全吃掉，变成一大块灰条。</p>
     */
    public View divider(int topDp, int bottomDp) {
        View view = divider();
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        params.topMargin = dp(topDp);
        params.bottomMargin = dp(bottomDp);
        return view;
    }

    // ------------------------------------------------------------------ 背景

    /** 带描边的卡片：靠一圈极细的边分层，不靠阴影。 */
    public GradientDrawable card(int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(SURFACE);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(Math.max(1, dp(2)), STROKE);
        return drawable;
    }

    /**
     * 卡片内部一块贴着<b>上边缘</b>的可点区域：静止透明，按下亮一档，只圆上两角。
     *
     * <p>只圆一侧是为了模拟「被卡片裁切」的效果。四角都圆的话，卡片内部会冒出一条弧线，
     * 看起来像里面又嵌了一张小卡片。</p>
     */
    public Drawable pressHighlightTop(int radiusDp) {
        return pressHighlight(true, radiusDp);
    }

    /** 同上，贴着<b>下边缘</b>的那块。 */
    public Drawable pressHighlightBottom(int radiusDp) {
        return pressHighlight(false, radiusDp);
    }

    private Drawable pressHighlight(boolean top, int radiusDp) {
        StateListDrawable list = new StateListDrawable();
        list.addState(new int[] { android.R.attr.state_pressed },
            halfRounded(top, SURFACE_HI, radiusDp));
        list.addState(new int[] { }, halfRounded(top, Color.TRANSPARENT, radiusDp));
        return list;
    }

    private GradientDrawable halfRounded(boolean top, int color, int radiusDp) {
        float r = dp(radiusDp);
        // 顺序：左上、右上、右下、左下，每个角两个值（x 半径、y 半径）
        float[] radii = top
            ? new float[] { r, r, r, r, 0, 0, 0, 0 }
            : new float[] { 0, 0, 0, 0, r, r, r, r };
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadii(radii);
        return drawable;
    }

    /** 屏幕底部那层浮起的提示条。 */
    public GradientDrawable toastBg() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.rgb(38, 51, 61));
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(Math.max(1, dp(2)), Color.rgb(62, 78, 92));
        return drawable;
    }

    private GradientDrawable field(int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(BG);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(Math.max(1, dp(2)), STROKE);
        return drawable;
    }

    private Drawable pressable(int normal, int pressed, int stroke, int radiusDp) {
        StateListDrawable list = new StateListDrawable();
        list.addState(new int[] { android.R.attr.state_pressed }, stroked(pressed, stroke, radiusDp));
        list.addState(new int[] { }, stroked(normal, stroke, radiusDp));
        return list;
    }

    private Drawable pressableFill(int normal, int pressed, int radiusDp) {
        StateListDrawable list = new StateListDrawable();
        list.addState(new int[] { android.R.attr.state_pressed }, filled(pressed, radiusDp));
        list.addState(new int[] { }, filled(normal, radiusDp));
        return list;
    }

    private GradientDrawable filled(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable stroked(int color, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (Color.alpha(stroke) != 0) drawable.setStroke(Math.max(1, dp(2)), stroke);
        return drawable;
    }

    // ------------------------------------------------------------------ 布局参数

    public LinearLayout.LayoutParams block() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int blend(int from, int to, float ratio) {
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * ratio);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * ratio);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ratio);
        return Color.rgb(r, g, b);
    }
}
