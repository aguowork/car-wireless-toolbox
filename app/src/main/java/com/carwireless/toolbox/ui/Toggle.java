package com.carwireless.toolbox.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 滑动开关。
 *
 * <p>4.2.2 自带的 {@code Switch} 是 Holo 时代的拟物样式——带渐变、带高光、带阴影，
 * 跟这套纯色扁平界面放一起会非常突兀，所以自己画一个：一条胶囊轨道 + 一个圆钮。</p>
 *
 * <p>开和关之间用 180ms 的位移动画过渡，不是硬切——硬切在车上看起来像卡了一下。</p>
 */
public final class Toggle extends View {

    private static final int TRACK_OFF = Color.rgb(42, 51, 61);
    private static final int TRACK_ON = Color.rgb(45, 212, 191);
    private static final int KNOB_OFF = Color.rgb(138, 151, 165);
    private static final int KNOB_ON = Color.rgb(11, 15, 20);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();

    private boolean checked;
    /** 0 = 全关，1 = 全开。动画期间是中间值。 */
    private float fraction;
    private ValueAnimator animator;

    public Toggle(Context context) {
        super(context);
        paint.setStyle(Paint.Style.FILL);
        setClickable(true);
    }

    public boolean isChecked() {
        return checked;
    }

    /** 第一次设初值时传 animate=false，避免开屏就看到一段莫名其妙的滑动。 */
    public void setChecked(boolean value, boolean animate) {
        checked = value;
        float target = value ? 1f : 0f;
        if (!animate || getWidth() == 0) {
            stopAnimation();
            fraction = target;
            invalidate();
            return;
        }
        if (Math.abs(fraction - target) < 0.01f) return;

        stopAnimation();
        animator = ValueAnimator.ofFloat(fraction, target);
        animator.setDuration(180L);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator animation) {
                fraction = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    private void stopAnimation() {
        if (animator == null) return;
        animator.cancel();
        animator = null;
    }

    @Override protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;

        float radius = height / 2f;

        paint.setColor(blend(TRACK_OFF, TRACK_ON, fraction));
        track.set(0f, 0f, width, height);
        canvas.drawRoundRect(track, radius, radius, paint);

        float inset = height * 0.105f;
        float knobRadius = radius - inset;
        float cx = radius + (width - radius * 2f) * fraction;
        paint.setColor(blend(KNOB_OFF, KNOB_ON, fraction));
        canvas.drawCircle(cx, radius, knobRadius, paint);
    }

    private static int blend(int from, int to, float ratio) {
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * ratio);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * ratio);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ratio);
        return Color.rgb(r, g, b);
    }
}
