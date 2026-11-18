package com.carwireless.toolbox.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * 「更多」图标：三个点，全用 Canvas 画。
 *
 * <p>为什么不用系统自带图标：4.2.2 的 {@code android.R.drawable} 是 Holo 时代的
 * 拟物风格，跟这一版的扁平界面完全不搭；也没有矢量图 API（VectorDrawable 要 API 21）。
 * 与其塞 png，不如直接画——十行代码，任意尺寸都清晰。</p>
 */
public final class IconView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int tint;

    public IconView(Context context, int tint) {
        super(context);
        this.tint = tint;
        paint.setStyle(Paint.Style.FILL);
    }

    @Override protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        paint.setColor(tint);
        float radius = size * 0.09f;
        float gap = size * 0.31f;
        canvas.drawCircle(cx - gap, cy, radius, paint);
        canvas.drawCircle(cx, cy, radius, paint);
        canvas.drawCircle(cx + gap, cy, radius, paint);
    }
}
