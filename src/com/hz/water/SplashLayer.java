package com.hz.water;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 原生启动层.
 *
 * 为什么不用 theme 的 windowBackground 铺一张 JPG:
 * 那张图是固定像素的, 横屏时会被 fill 拉伸变形. 这里改成代码堆视图,
 * 全用 dp/sp 定位, 横竖屏都不变形.
 *
 * 内容只有一行 "扫码饮水 · 自助洗衣" —— 图标与名称已经在桌面图标上出现过,
 * 开屏再放一次等于同一个东西播两遍. 这里和页面 #boot 用同一位置同一字号,
 * 所以原生层淡出、页面接管时那一行字是连着的, 看不出切换.
 */
final class SplashLayer extends FrameLayout {

    static final int BG_TOP = 0xFF14477F;
    static final int BG_MID = 0xFF0D2B54;
    static final int BG_BOT = 0xFF081726;
    static final int TXT_SUB = 0xFF9FC0E4;

    SplashLayer(Context c) {
        super(c);

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{BG_TOP, BG_MID, BG_BOT});
        setBackgroundDrawable(bg);

        TextView sub = new TextView(c);
        sub.setText("扫码饮水 · 自助洗衣");
        sub.setTextColor(TXT_SUB);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sub.setGravity(Gravity.CENTER);
        sub.setLetterSpacing(0.08f);

        LayoutParams lp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        addView(sub, lp);
    }
}
