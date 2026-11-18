package com.carwireless.toolbox.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * 大圆环开关。
 *
 * <p>整个界面只有这一个主操作，所以它必须是最有分量的东西。做法不是把圆圈画粗，
 * 而是给它<b>纵深</b>：开启时圆环外面套一圈很淡的青色光晕，环内的深色盘透出一点青，
 * 视觉上像一块真的在发光的圆盘，而不是一条描边线。</p>
 *
 * <p>视图尺寸比可见圆环大一圈，留给光晕扩散。所有比例都按视图边长算，
 * 所以给多少 dp 都不会画歪。</p>
 *
 * <p>图形全部用 Canvas 画，不依赖图片资源——车机是 Android 4.2.2，
 * 系统字体里没有电源符号这类字形，用文字画图标会显示成方块。</p>
 */
public final class PowerRing extends View {

    public static final int STATE_OFF = 0;
    public static final int STATE_ON = 1;
    public static final int STATE_BUSY = 2;

    /** 可见圆环半径占视图边长的比例。剩下的边留给光晕。 */
    private static final float RING_RATIO = 0.44f;
    private static final float GLOW_RATIO = 0.49f;
    /** 环的粗细按视图边长算，不是固定 dp——放大缩小时比例才不会变形。 */
    private static final float STROKE_RATIO = 0.055f;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private int state = STATE_OFF;
    private int accent = Color.rgb(45, 212, 191);
    private int idle = Color.rgb(58, 71, 83);
    private int idleFill = Color.rgb(29, 37, 46);

    private Shader glowShader;
    private int glowBuiltFor = -1;

    private float phase;
    private boolean spinning;

    public PowerRing(Context context) {
        super(context);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setStrokeJoin(Paint.Join.ROUND);
        glowPaint.setStyle(Paint.Style.FILL);
    }

    public void setColors(int accentColor, int idleColor, int idleFillColor) {
        this.accent = accentColor;
        this.idle = idleColor;
        this.idleFill = idleFillColor;
        this.glowShader = null;
        invalidate();
    }

    public void setState(int value) {
        if (state == value) return;
        state = value;
        if (state == STATE_BUSY) startSpin();
        else stopSpin();
        invalidate();
    }

    // ------------------------------------------------------------------ 转圈

    private final Runnable spinner = new Runnable() {
        @Override public void run() {
            if (!spinning) return;
            phase = (phase + 9f) % 360f;
            invalidate();
            postDelayed(this, 30L);
        }
    };

    private void startSpin() {
        if (spinning) return;
        spinning = true;
        phase = 0f;
        post(spinner);
    }

    private void stopSpin() {
        spinning = false;
        removeCallbacks(spinner);
    }

    @Override protected void onDetachedFromWindow() {
        stopSpin();
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ 绘制

    @Override protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        int size = Math.min(width, height);
        float cx = width / 2f;
        float cy = height / 2f;

        float ringRadius = size * RING_RATIO;
        float ringStroke = size * STROKE_RATIO;
        float innerRadius = ringRadius - ringStroke * 1.35f;
        boolean on = state == STATE_ON;

        // 光晕先画，它决定整块区域的「体积感」
        if (on) {
            float glowRadius = size * GLOW_RATIO;
            glowPaint.setShader(glowShader(size, cx, cy, glowRadius));
            canvas.drawCircle(cx, cy, glowRadius, glowPaint);
        }

        // 环内的盘：开启时透一点青，关闭时是一块安静的深色
        fillPaint.setColor(on ? blend(idleFill, accent, 0.22f) : idleFill);
        canvas.drawCircle(cx, cy, innerRadius, fillPaint);

        // 外环
        if (state == STATE_BUSY) {
            ringPaint.setStrokeWidth(ringStroke);
            ringPaint.setColor(blend(idle, accent, 0.35f));
            canvas.drawCircle(cx, cy, ringRadius, ringPaint);

            ringPaint.setColor(accent);
            arcRect.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius);
            canvas.drawArc(arcRect, phase, 70f, false, ringPaint);
        } else {
            ringPaint.setStrokeWidth(ringStroke);
            ringPaint.setColor(on ? accent : idle);
            canvas.drawCircle(cx, cy, ringRadius, ringPaint);
        }

        // 电源符号：上方开口的圆 + 一根竖线
        float glyphRadius = size * 0.145f;
        glyphPaint.setStrokeWidth(size * 0.062f);
        glyphPaint.setColor(state == STATE_BUSY || on
            ? accent
            : blend(idle, Color.rgb(242, 246, 250), 0.45f));

        arcRect.set(cx - glyphRadius, cy - glyphRadius * 0.92f,
            cx + glyphRadius, cy + glyphRadius * 1.08f);
        canvas.drawArc(arcRect, -55f, 290f, false, glyphPaint);
        canvas.drawLine(cx, cy - glyphRadius * 1.12f,
            cx, cy - glyphRadius * 0.05f, glyphPaint);
    }

    /** 光晕只在尺寸变化时重建，避免每帧 new 一个 Shader。 */
    private Shader glowShader(int size, float cx, float cy, float radius) {
        if (glowShader != null && glowBuiltFor == size) return glowShader;
        int edge = Color.argb(70, Color.red(accent), Color.green(accent), Color.blue(accent));
        int clear = Color.argb(0, Color.red(accent), Color.green(accent), Color.blue(accent));
        glowShader = new RadialGradient(cx, cy, radius,
            new int[] { edge, clear }, new float[] { 0.62f, 1f }, Shader.TileMode.CLAMP);
        glowBuiltFor = size;
        return glowShader;
    }

    /** 按比例把两个颜色混一混，用来做「透一点色」的效果。 */
    private static int blend(int from, int to, float ratio) {
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * ratio);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * ratio);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ratio);
        return Color.rgb(r, g, b);
    }
}
